package com.fraud.api.kafka.consumer;

import com.fraud.api.domain.Payment;
import com.fraud.api.domain.RiskEvent;
import com.fraud.api.kafka.KafkaTopicConfig;
import com.fraud.api.repository.PaymentRepository;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.api.service.WebhookService;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.domain.StepStatus;
import com.fraud.common.event.PaymentEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Rule Consumer: Final stage of the Kafka pipeline.
 * 
 * Responsibilities:
 * - Apply business rules
 * - Make final decision (ALLOW/REVIEW/BLOCK)
 * - Persist to database
 * - Send webhook notification
 */
@Component
@Slf4j
public class RuleConsumer {

    private static final BigDecimal HIGH_RISK_THRESHOLD = new BigDecimal("0.7");
    private static final BigDecimal MEDIUM_RISK_THRESHOLD = new BigDecimal("0.4");
    private static final Set<String> BLACKLISTED_BINS = Set.of("000000", "111111", "999999");
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of("RU", "NG", "PK");

    private final PaymentRepository paymentRepository;
    private final RiskEventRepository riskEventRepository;
    private final WebhookService webhookService;
    private final Counter allowCounter;
    private final Counter reviewCounter;
    private final Counter blockCounter;
    private final Timer processingTimer;

    public RuleConsumer(
            PaymentRepository paymentRepository,
            RiskEventRepository riskEventRepository,
            WebhookService webhookService,
            MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.riskEventRepository = riskEventRepository;
        this.webhookService = webhookService;
        this.allowCounter = Counter.builder("kafka.rule.decision")
                .tag("decision", "ALLOW").register(meterRegistry);
        this.reviewCounter = Counter.builder("kafka.rule.decision")
                .tag("decision", "REVIEW").register(meterRegistry);
        this.blockCounter = Counter.builder("kafka.rule.decision")
                .tag("decision", "BLOCK").register(meterRegistry);
        this.processingTimer = Timer.builder("kafka.rule.duration")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_RULE,
            groupId = "fraud-rule-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        Timer.Sample sample = Timer.start();
        
        MDC.put("payment_id", event.getPaymentId());
        MDC.put("step", "RULE");

        try {
            log.info("Received rule event: riskScore={}", event.getRiskScore());

            // 1. Idempotency check
            if (isAlreadyProcessed(event.getPaymentId())) {
                log.info("Already processed, skipping");
                ack.acknowledge();
                return;
            }

            // 2. Apply rules
            Map<String, Boolean> ruleResults = applyRules(event);

            // 3. Make decision
            Decision decision = makeDecision(event.getRiskScore(), ruleResults);
            PaymentState state = mapDecisionToState(decision);

            // 4. Update event
            event.withRuleResults(ruleResults);
            event.withDecision(decision, state);

            // 5. Persist to database
            Payment payment = persistPayment(event, decision, state);

            // 6. Record step done
            saveRiskEvent(event.getPaymentId(), decision, ruleResults);

            // 7. Send webhook (if not REVIEW)
            if (decision != Decision.REVIEW) {
                webhookService.sendDecisionWebhook(payment);
            }

            // 8. Update metrics
            updateMetrics(decision);

            // 9. Acknowledge
            ack.acknowledge();

            log.info("Rule evaluation completed: decision={}", decision);

        } catch (Exception e) {
            log.error("Rule evaluation failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            sample.stop(processingTimer);
            MDC.clear();
        }
    }

    private boolean isAlreadyProcessed(String paymentId) {
        return riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.DECISION, StepStatus.DONE);
    }

    private Map<String, Boolean> applyRules(PaymentEvent event) {
        Map<String, Boolean> results = new HashMap<>();
        Map<String, Object> features = event.getFeatures();
        
        // Rule 1: Blacklisted BIN
        boolean blacklistedBin = BLACKLISTED_BINS.contains(event.getCardBin());
        results.put("blacklisted_bin", blacklistedBin);
        
        // Rule 2: High risk country
        String ipCountry = (String) features.getOrDefault("ip_country", "");
        boolean highRiskCountry = HIGH_RISK_COUNTRIES.contains(ipCountry);
        results.put("high_risk_country", highRiskCountry);
        
        // Rule 3: Proxy IP
        boolean isProxy = (Boolean) features.getOrDefault("ip_is_proxy", false);
        results.put("proxy_ip", isProxy);
        
        // Rule 4: High velocity
        int deviceTxCount = ((Number) features.getOrDefault("device_tx_count_24h", 0)).intValue();
        boolean highVelocity = deviceTxCount > 15;
        results.put("high_velocity", highVelocity);
        
        // Rule 5: Large amount
        double amountPercentile = ((Number) features.getOrDefault("amount_percentile", 0.0)).doubleValue();
        boolean largeAmount = amountPercentile > 0.95;
        results.put("large_amount", largeAmount);
        
        // Rule 6: Late night transaction
        int hour = ((Number) features.getOrDefault("hour_of_day", 12)).intValue();
        boolean lateNight = hour >= 0 && hour < 5;
        results.put("late_night", lateNight);
        
        return results;
    }

    private Decision makeDecision(BigDecimal riskScore, Map<String, Boolean> ruleResults) {
        // Immediate BLOCK conditions
        if (ruleResults.getOrDefault("blacklisted_bin", false)) {
            return Decision.BLOCK;
        }
        if (ruleResults.getOrDefault("proxy_ip", false) && 
            ruleResults.getOrDefault("high_risk_country", false)) {
            return Decision.BLOCK;
        }
        
        // Score-based decision
        if (riskScore.compareTo(HIGH_RISK_THRESHOLD) >= 0) {
            return Decision.BLOCK;
        }
        if (riskScore.compareTo(MEDIUM_RISK_THRESHOLD) >= 0) {
            return Decision.REVIEW;
        }
        
        // Additional REVIEW conditions
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

    private Payment persistPayment(PaymentEvent event, Decision decision, PaymentState state) {
        // Check if payment already exists (idempotency)
        Payment payment = paymentRepository.findById(event.getPaymentId())
                .orElseGet(() -> createPayment(event));
        
        // Update with decision
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

    private void saveRiskEvent(String paymentId, Decision decision, Map<String, Boolean> ruleResults) {
        Map<String, Object> details = new HashMap<>();
        details.put("decision", decision.name());
        details.putAll(ruleResults);
        
        RiskEvent event = RiskEvent.done(paymentId, PipelineStep.DECISION, details);
        riskEventRepository.save(event);
    }

    private void updateMetrics(Decision decision) {
        switch (decision) {
            case ALLOW -> allowCounter.increment();
            case REVIEW -> reviewCounter.increment();
            case BLOCK -> blockCounter.increment();
        }
    }
}
