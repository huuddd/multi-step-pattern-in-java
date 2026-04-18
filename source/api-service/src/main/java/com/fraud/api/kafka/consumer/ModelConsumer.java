package com.fraud.api.kafka.consumer;

import com.fraud.api.domain.RiskEvent;
import com.fraud.api.kafka.KafkaTopicConfig;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.domain.StepStatus;
import com.fraud.common.event.PaymentEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Model Consumer: Third stage of the Kafka pipeline.
 * 
 * Responsibilities:
 * - Calculate risk score based on features
 * - This is CPU-bound (ML inference simulation)
 * - Forward to rule topic
 */
@Component
@Slf4j
public class ModelConsumer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    private final RiskEventRepository riskEventRepository;
    private final Counter processedCounter;
    private final Timer processingTimer;

    public ModelConsumer(
            KafkaTemplate<String, PaymentEvent> kafkaTemplate,
            RiskEventRepository riskEventRepository,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.riskEventRepository = riskEventRepository;
        this.processedCounter = Counter.builder("kafka.model.processed")
                .register(meterRegistry);
        this.processingTimer = Timer.builder("kafka.model.duration")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_MODEL,
            groupId = "fraud-model-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        Timer.Sample sample = Timer.start();
        
        MDC.put("payment_id", event.getPaymentId());
        MDC.put("step", "MODEL");

        try {
            log.info("Received model event");

            // 1. Idempotency check
            if (isAlreadyProcessed(event.getPaymentId())) {
                log.info("Already processed, skipping");
                ack.acknowledge();
                return;
            }

            // 2. Calculate risk score (CPU-bound)
            BigDecimal riskScore = calculateRiskScore(event);

            // 3. Enrich event with score
            PaymentEvent scored = event.withRiskScore(riskScore);

            // 4. Record step done
            saveRiskEvent(event.getPaymentId(), riskScore);

            // 5. Forward to rule topic
            kafkaTemplate.send(KafkaTopicConfig.TOPIC_RULE, key, scored);

            // 6. Acknowledge
            ack.acknowledge();
            processedCounter.increment();

            log.info("Model scoring completed: score={}, forwarded to rule topic", riskScore);

        } catch (Exception e) {
            log.error("Model scoring failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            sample.stop(processingTimer);
            MDC.clear();
        }
    }

    private boolean isAlreadyProcessed(String paymentId) {
        return riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.MODEL, StepStatus.DONE);
    }

    private BigDecimal calculateRiskScore(PaymentEvent event) {
        Map<String, Object> features = event.getFeatures();
        
        double score = 0.0;
        
        // Amount percentile contribution
        double amountPercentile = getDouble(features, "amount_percentile", 0.5);
        score += amountPercentile * 0.25;
        
        // Card BIN risk contribution
        String binRisk = getString(features, "card_bin_risk_level", "MEDIUM");
        switch (binRisk) {
            case "HIGH" -> score += 0.3;
            case "MEDIUM" -> score += 0.15;
            case "LOW" -> score += 0.05;
        }
        
        // Device velocity contribution
        int deviceTxCount = getInt(features, "device_tx_count_24h", 0);
        if (deviceTxCount > 10) score += 0.2;
        else if (deviceTxCount > 5) score += 0.1;
        
        // IP risk contribution
        boolean isProxy = getBoolean(features, "ip_is_proxy", false);
        if (isProxy) score += 0.25;
        
        String ipCountry = getString(features, "ip_country", "VN");
        if ("RU".equals(ipCountry) || "CN".equals(ipCountry)) {
            score += 0.1;
        }
        
        // Time-based risk
        int hour = getInt(features, "hour_of_day", 12);
        if (hour >= 0 && hour < 6) score += 0.1; // Late night transactions
        
        // Simulate CPU work (ML inference)
        simulateCpuWork();
        
        // Add small random noise
        score += Math.random() * 0.05;
        
        // Clamp to [0, 1]
        score = Math.max(0.0, Math.min(1.0, score));
        
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private void simulateCpuWork() {
        // Simulate 10-30ms of CPU work
        long iterations = 50000 + (long)(Math.random() * 100000);
        double result = 0;
        for (long i = 0; i < iterations; i++) {
            result += Math.sin(i) * Math.cos(i);
        }
        if (result == Double.MAX_VALUE) {
            log.trace("Impossible");
        }
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

    private void saveRiskEvent(String paymentId, BigDecimal riskScore) {
        RiskEvent event = RiskEvent.done(paymentId, PipelineStep.MODEL, 
                Map.of("risk_score", riskScore.toString()));
        riskEventRepository.save(event);
    }
}
