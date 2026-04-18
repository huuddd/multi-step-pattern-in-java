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

import java.util.Map;

/**
 * Ingest Consumer: First stage of the Kafka pipeline.
 * 
 * Responsibilities:
 * - Validate incoming payment event
 * - Normalize data (trim, lowercase, etc.)
 * - Check idempotency
 * - Forward to feature topic
 */
@Component
@Slf4j
public class IngestConsumer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    private final RiskEventRepository riskEventRepository;
    private final Counter processedCounter;
    private final Counter skippedCounter;
    private final Timer processingTimer;

    public IngestConsumer(
            KafkaTemplate<String, PaymentEvent> kafkaTemplate,
            RiskEventRepository riskEventRepository,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.riskEventRepository = riskEventRepository;
        this.processedCounter = Counter.builder("kafka.ingest.processed")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder("kafka.ingest.skipped")
                .register(meterRegistry);
        this.processingTimer = Timer.builder("kafka.ingest.duration")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_INGEST,
            groupId = "fraud-ingest-group",
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
        MDC.put("step", "INGEST");
        MDC.put("partition", String.valueOf(partition));
        MDC.put("offset", String.valueOf(offset));

        try {
            log.info("Received ingest event: key={}", key);

            // 1. Idempotency check
            if (isAlreadyProcessed(event.getPaymentId())) {
                log.info("Already processed, skipping");
                skippedCounter.increment();
                ack.acknowledge();
                return;
            }

            // 2. Validate
            validateEvent(event);

            // 3. Normalize
            PaymentEvent normalized = normalizeEvent(event);

            // 4. Record step start
            saveRiskEvent(event.getPaymentId(), StepStatus.STARTED, null);

            // 5. Mark as ingested
            normalized.markIngested();

            // 6. Forward to feature topic
            kafkaTemplate.send(KafkaTopicConfig.TOPIC_FEATURE, key, normalized)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send to feature topic", ex);
                        } else {
                            log.debug("Sent to feature topic: partition={}, offset={}",
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });

            // 7. Record step done
            saveRiskEvent(event.getPaymentId(), StepStatus.DONE, Map.of("normalized", true));

            // 8. Acknowledge
            ack.acknowledge();
            processedCounter.increment();

            log.info("Ingest completed, forwarded to feature topic");

        } catch (Exception e) {
            log.error("Ingest failed: {}", e.getMessage(), e);
            saveRiskEvent(event.getPaymentId(), StepStatus.FAILED, e.getMessage());
            throw e; // Trigger retry
        } finally {
            sample.stop(processingTimer);
            MDC.clear();
        }
    }

    private boolean isAlreadyProcessed(String paymentId) {
        return riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.INGEST, StepStatus.DONE);
    }

    private void validateEvent(PaymentEvent event) {
        if (event.getPaymentId() == null || event.getPaymentId().isBlank()) {
            throw new IllegalArgumentException("payment_id is required");
        }
        if (event.getMerchantId() == null || event.getMerchantId().isBlank()) {
            throw new IllegalArgumentException("merchant_id is required");
        }
        if (event.getAmount() == null || event.getAmount() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    private PaymentEvent normalizeEvent(PaymentEvent event) {
        // Normalize currency to uppercase
        if (event.getCurrency() != null) {
            event.setCurrency(event.getCurrency().toUpperCase());
        }
        // Normalize IP (trim whitespace)
        if (event.getIp() != null) {
            event.setIp(event.getIp().trim());
        }
        return event;
    }

    private void saveRiskEvent(String paymentId, StepStatus status, Object details) {
        RiskEvent riskEvent;
        if (status == StepStatus.DONE) {
            riskEvent = RiskEvent.done(paymentId, PipelineStep.INGEST, 
                    details instanceof Map ? (Map<String, Object>) details : Map.of());
        } else if (status == StepStatus.FAILED) {
            riskEvent = RiskEvent.failed(paymentId, PipelineStep.INGEST, 
                    details != null ? details.toString() : "Unknown error");
        } else {
            riskEvent = RiskEvent.started(paymentId, PipelineStep.INGEST);
        }
        riskEventRepository.save(riskEvent);
    }
}
