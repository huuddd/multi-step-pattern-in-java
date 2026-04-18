package com.fraud.api.rabbitmq;

import com.fraud.api.domain.Payment;
import com.fraud.api.domain.RiskEvent;
import com.fraud.api.repository.PaymentRepository;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.api.service.WebhookService;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.domain.StepStatus;
import com.fraud.common.event.PaymentEvent;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Consumer for per-merchant queues.
 * 
 * Each consumer processes messages from merchant-specific queues.
 * With prefetchCount=1, each consumer processes one message at a time,
 * ensuring fair dispatch and preventing noisy neighbor issues.
 * 
 * This consumer handles the FULL pipeline:
 * INGEST → FEATURE → MODEL → RULE → DECISION
 * 
 * For production, consider splitting into separate consumers per stage.
 */
@Component
@Slf4j
public class MerchantConsumer {

    private static final BigDecimal HIGH_RISK_THRESHOLD = new BigDecimal("0.7");
    private static final BigDecimal MEDIUM_RISK_THRESHOLD = new BigDecimal("0.4");
    private static final Set<String> BLACKLISTED_BINS = Set.of("000000", "111111", "999999");

    private final PaymentRepository paymentRepository;
    private final RiskEventRepository riskEventRepository;
    private final WebhookService webhookService;
    private final PerMerchantRateLimiter rateLimiter;
    private final Counter processedCounter;
    private final Counter rejectedCounter;
    private final Timer processingTimer;
    private final Random random = new Random();

    public MerchantConsumer(
            PaymentRepository paymentRepository,
            RiskEventRepository riskEventRepository,
            WebhookService webhookService,
            PerMerchantRateLimiter rateLimiter,
            MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.riskEventRepository = riskEventRepository;
        this.webhookService = webhookService;
        this.rateLimiter = rateLimiter;
        this.processedCounter = Counter.builder("rabbitmq.messages.processed")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("rabbitmq.messages.rejected")
                .tag("reason", "rate_limit")
                .register(meterRegistry);
        this.processingTimer = Timer.builder("rabbitmq.processing.duration")
                .register(meterRegistry);
    }

    /**
     * Process message from merchant queue.
     * 
     * Note: In production, use dynamic queue binding or multiple listeners.
     * This example uses a pattern to match all merchant.* queues.
     */
    @RabbitListener(
            queues = "#{@merchantQueueNames}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    @Transactional
    public void consume(
            @Payload PaymentEvent event,
            @Header(AmqpHeaders.CHANNEL) Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {

        Timer.Sample sample = Timer.start();
        String merchantId = event.getMerchantId();
        String paymentId = event.getPaymentId();

        MDC.put("payment_id", paymentId);
        MDC.put("merchant_id", merchantId);

        try {
            log.info("Processing payment from merchant queue");

            // 1. Rate limiting check
            if (!rateLimiter.tryAcquire(merchantId)) {
                log.warn("Rate limit exceeded for merchant: {}", merchantId);
                rejectedCounter.increment();
                // Requeue for later processing
                channel.basicNack(deliveryTag, false, true);
                return;
            }

            // 2. Idempotency check
            if (isAlreadyProcessed(paymentId)) {
                log.info("Already processed, acknowledging");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. Process full pipeline
            Decision decision = processPipeline(event);

            // 4. Persist payment
            Payment payment = persistPayment(event, decision);

            // 5. Send webhook
            if (decision != Decision.REVIEW) {
                webhookService.sendDecisionWebhook(payment);
            }

            // 6. Acknowledge
            channel.basicAck(deliveryTag, false);
            processedCounter.increment();

            log.info("Payment processed: decision={}", decision);

        } catch (Exception e) {
            log.error("Failed to process payment: {}", e.getMessage(), e);
            
            // Record failure
            saveRiskEvent(paymentId, PipelineStep.DECISION, StepStatus.FAILED, 
                    Map.of("error", e.getMessage()));
            
            // Reject and don't requeue (will go to DLQ)
            channel.basicNack(deliveryTag, false, false);
            
        } finally {
            sample.stop(processingTimer);
            MDC.clear();
        }
    }

    private boolean isAlreadyProcessed(String paymentId) {
        return riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.DECISION, StepStatus.DONE);
    }

    private Decision processPipeline(PaymentEvent event) {
        String paymentId = event.getPaymentId();

        // Stage 1: INGEST
        event.markIngested();
        saveRiskEvent(paymentId, PipelineStep.INGEST, StepStatus.DONE, Map.of());

        // Stage 2: FEATURE
        Map<String, Object> features = extractFeatures(event);
        event.withFeatures(features);
        saveRiskEvent(paymentId, PipelineStep.FEATURE, StepStatus.DONE, features);

        // Stage 3: MODEL
        BigDecimal riskScore = calculateRiskScore(event);
        event.withRiskScore(riskScore);
        saveRiskEvent(paymentId, PipelineStep.MODEL, StepStatus.DONE, 
                Map.of("risk_score", riskScore.toString()));

        // Stage 4: RULE
        Map<String, Boolean> ruleResults = applyRules(event);
        event.withRuleResults(ruleResults);
        saveRiskEvent(paymentId, PipelineStep.RULE, StepStatus.DONE, 
                new HashMap<>(ruleResults));

        // Stage 5: DECISION
        Decision decision = makeDecision(riskScore, ruleResults);
        PaymentState state = mapDecisionToState(decision);
        event.withDecision(decision, state);
        
        Map<String, Object> decisionDetails = new HashMap<>();
        decisionDetails.put("decision", decision.name());
        decisionDetails.put("state", state.name());
        saveRiskEvent(paymentId, PipelineStep.DECISION, StepStatus.DONE, decisionDetails);

        return decision;
    }

    private Map<String, Object> extractFeatures(PaymentEvent event) {
        Map<String, Object> features = new HashMap<>();
        features.put("merchant_tx_count_24h", random.nextInt(100));
        features.put("device_tx_count_24h", random.nextInt(20));
        features.put("card_bin_risk_level", getCardBinRiskLevel(event.getCardBin()));
        features.put("ip_country", getIpCountry(event.getIp()));
        features.put("ip_is_proxy", random.nextDouble() < 0.05);
        features.put("amount_percentile", calculateAmountPercentile(event.getAmount()));
        features.put("hour_of_day", java.time.LocalTime.now().getHour());
        return features;
    }

    private String getCardBinRiskLevel(String cardBin) {
        if (cardBin == null) return "UNKNOWN";
        int binNum = Integer.parseInt(cardBin.substring(0, Math.min(2, cardBin.length())));
        if (binNum < 20) return "HIGH";
        if (binNum < 50) return "MEDIUM";
        return "LOW";
    }

    private String getIpCountry(String ip) {
        if (ip == null) return "UNKNOWN";
        String[] countries = {"VN", "US", "SG", "JP", "CN", "RU"};
        return countries[Math.abs(ip.hashCode()) % countries.length];
    }

    private double calculateAmountPercentile(Long amount) {
        if (amount < 100000) return 0.2;
        if (amount < 500000) return 0.5;
        if (amount < 2000000) return 0.8;
        return 0.95;
    }

    private BigDecimal calculateRiskScore(PaymentEvent event) {
        Map<String, Object> features = event.getFeatures();
        double score = 0.0;

        double amountPercentile = getDouble(features, "amount_percentile", 0.5);
        score += amountPercentile * 0.25;

        String binRisk = getString(features, "card_bin_risk_level", "MEDIUM");
        switch (binRisk) {
            case "HIGH" -> score += 0.3;
            case "MEDIUM" -> score += 0.15;
            case "LOW" -> score += 0.05;
        }

        int deviceTxCount = getInt(features, "device_tx_count_24h", 0);
        if (deviceTxCount > 10) score += 0.2;
        else if (deviceTxCount > 5) score += 0.1;

        boolean isProxy = getBoolean(features, "ip_is_proxy", false);
        if (isProxy) score += 0.25;

        // Simulate CPU work
        simulateCpuWork();

        score += Math.random() * 0.05;
        score = Math.max(0.0, Math.min(1.0, score));

        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private void simulateCpuWork() {
        long iterations = 50000 + (long)(Math.random() * 100000);
        double result = 0;
        for (long i = 0; i < iterations; i++) {
            result += Math.sin(i) * Math.cos(i);
        }
        if (result == Double.MAX_VALUE) log.trace("Impossible");
    }

    private Map<String, Boolean> applyRules(PaymentEvent event) {
        Map<String, Boolean> results = new HashMap<>();
        Map<String, Object> features = event.getFeatures();

        results.put("blacklisted_bin", BLACKLISTED_BINS.contains(event.getCardBin()));
        results.put("high_risk_country", Set.of("RU", "NG", "PK")
                .contains(getString(features, "ip_country", "")));
        results.put("proxy_ip", getBoolean(features, "ip_is_proxy", false));
        results.put("high_velocity", getInt(features, "device_tx_count_24h", 0) > 15);
        results.put("large_amount", getDouble(features, "amount_percentile", 0.0) > 0.95);

        return results;
    }

    private Decision makeDecision(BigDecimal riskScore, Map<String, Boolean> ruleResults) {
        if (ruleResults.getOrDefault("blacklisted_bin", false)) {
            return Decision.BLOCK;
        }
        if (ruleResults.getOrDefault("proxy_ip", false) &&
            ruleResults.getOrDefault("high_risk_country", false)) {
            return Decision.BLOCK;
        }
        if (riskScore.compareTo(HIGH_RISK_THRESHOLD) >= 0) {
            return Decision.BLOCK;
        }
        if (riskScore.compareTo(MEDIUM_RISK_THRESHOLD) >= 0) {
            return Decision.REVIEW;
        }
        if (ruleResults.getOrDefault("high_velocity", false) ||
            ruleResults.getOrDefault("large_amount", false)) {
            return Decision.REVIEW;
        }
        return Decision.ALLOW;
    }

    private PaymentState mapDecisionToState(Decision decision) {
        return switch (decision) {
            case ALLOW -> PaymentState.DECIDED;
            case REVIEW -> PaymentState.REVIEW;
            case BLOCK -> PaymentState.BLOCKED;
        };
    }

    private Payment persistPayment(PaymentEvent event, Decision decision) {
        Payment payment = paymentRepository.findById(event.getPaymentId())
                .orElseGet(() -> createPayment(event));
        payment.complete(decision, event.getRiskScore());
        return paymentRepository.save(payment);
    }

    private Payment createPayment(PaymentEvent event) {
        Payment payment = new Payment();
        payment.setPaymentId(event.getPaymentId());
        payment.setMerchantId(event.getMerchantId());
        payment.setAmount(event.getAmount());
        payment.setCurrency(event.getCurrency());
        payment.setCardBin(event.getCardBin());
        payment.setIp(event.getIp());
        payment.setDeviceId(event.getDeviceId());
        payment.setIdempotencyKey(event.getIdempotencyKey());
        payment.setState(PaymentState.PENDING);
        return payment;
    }

    private void saveRiskEvent(String paymentId, PipelineStep step, StepStatus status, 
                               Map<String, Object> details) {
        RiskEvent event;
        if (status == StepStatus.DONE) {
            event = RiskEvent.done(paymentId, step, details);
        } else {
            event = RiskEvent.failed(paymentId, step, 
                    details.getOrDefault("error", "Unknown").toString());
        }
        riskEventRepository.save(event);
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        return defaultValue;
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        return defaultValue;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value instanceof String) return (String) value;
        return defaultValue;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        return defaultValue;
    }
}
