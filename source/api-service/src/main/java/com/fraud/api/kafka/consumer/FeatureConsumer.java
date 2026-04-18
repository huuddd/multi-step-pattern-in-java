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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Feature Consumer: Second stage of the Kafka pipeline.
 * 
 * Responsibilities:
 * - Extract features from payment data
 * - Enrich with external data (simulated)
 * - Forward to model topic
 */
@Component
@Slf4j
public class FeatureConsumer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    private final RiskEventRepository riskEventRepository;
    private final Counter processedCounter;
    private final Timer processingTimer;
    private final Random random = new Random();

    public FeatureConsumer(
            KafkaTemplate<String, PaymentEvent> kafkaTemplate,
            RiskEventRepository riskEventRepository,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.riskEventRepository = riskEventRepository;
        this.processedCounter = Counter.builder("kafka.feature.processed")
                .register(meterRegistry);
        this.processingTimer = Timer.builder("kafka.feature.duration")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_FEATURE,
            groupId = "fraud-feature-group",
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
        MDC.put("step", "FEATURE");

        try {
            log.info("Received feature event");

            // 1. Idempotency check
            if (isAlreadyProcessed(event.getPaymentId())) {
                log.info("Already processed, skipping");
                ack.acknowledge();
                return;
            }

            // 2. Extract features
            Map<String, Object> features = extractFeatures(event);

            // 3. Enrich event
            PaymentEvent enriched = event.withFeatures(features);

            // 4. Record step done
            saveRiskEvent(event.getPaymentId(), features);

            // 5. Forward to model topic
            kafkaTemplate.send(KafkaTopicConfig.TOPIC_MODEL, key, enriched);

            // 6. Acknowledge
            ack.acknowledge();
            processedCounter.increment();

            log.info("Feature extraction completed, forwarded to model topic");

        } catch (Exception e) {
            log.error("Feature extraction failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            sample.stop(processingTimer);
            MDC.clear();
        }
    }

    private boolean isAlreadyProcessed(String paymentId) {
        return riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.FEATURE, StepStatus.DONE);
    }

    private Map<String, Object> extractFeatures(PaymentEvent event) {
        Map<String, Object> features = new HashMap<>();
        
        // Merchant features (simulated)
        features.put("merchant_tx_count_24h", random.nextInt(100));
        features.put("merchant_avg_amount", event.getAmount() * (0.8 + random.nextDouble() * 0.4));
        
        // Device features (simulated)
        features.put("device_tx_count_24h", random.nextInt(20));
        features.put("device_unique_merchants", random.nextInt(5) + 1);
        
        // Card features (simulated)
        String cardBin = event.getCardBin();
        features.put("card_bin_risk_level", getCardBinRiskLevel(cardBin));
        features.put("card_tx_count_24h", random.nextInt(10));
        
        // IP features (simulated)
        features.put("ip_country", getIpCountry(event.getIp()));
        features.put("ip_is_proxy", random.nextDouble() < 0.05);
        
        // Amount features
        features.put("amount_percentile", calculateAmountPercentile(event.getAmount()));
        features.put("amount_vs_merchant_avg", 1.0 + (random.nextDouble() - 0.5));
        
        // Time features
        features.put("hour_of_day", java.time.LocalTime.now().getHour());
        features.put("is_weekend", java.time.LocalDate.now().getDayOfWeek().getValue() >= 6);
        
        return features;
    }

    private String getCardBinRiskLevel(String cardBin) {
        if (cardBin == null) return "UNKNOWN";
        // Simulated risk levels based on BIN
        int binNum = Integer.parseInt(cardBin.substring(0, Math.min(2, cardBin.length())));
        if (binNum < 20) return "HIGH";
        if (binNum < 50) return "MEDIUM";
        return "LOW";
    }

    private String getIpCountry(String ip) {
        if (ip == null) return "UNKNOWN";
        // Simulated country lookup
        String[] countries = {"VN", "US", "SG", "JP", "CN", "RU"};
        return countries[Math.abs(ip.hashCode()) % countries.length];
    }

    private double calculateAmountPercentile(Long amount) {
        // Simulated percentile calculation
        if (amount < 100000) return 0.2;
        if (amount < 500000) return 0.5;
        if (amount < 2000000) return 0.8;
        return 0.95;
    }

    private void saveRiskEvent(String paymentId, Map<String, Object> features) {
        RiskEvent event = RiskEvent.done(paymentId, PipelineStep.FEATURE, features);
        riskEventRepository.save(event);
    }
}
