package com.fraud.api.kafka.consumer;

import com.fraud.api.domain.RiskEvent;
import com.fraud.api.kafka.KafkaTopicConfig;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.event.PaymentEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Dead Letter Queue Handler.
 * 
 * Processes messages that have failed all retry attempts.
 * Responsibilities:
 * - Log the failure
 * - Store in database for manual review
 * - Send alerts
 * - Update metrics
 */
@Component
@Slf4j
public class DlqHandler {

    private final RiskEventRepository riskEventRepository;
    private final Counter dlqCounter;

    public DlqHandler(
            RiskEventRepository riskEventRepository,
            MeterRegistry meterRegistry) {
        this.riskEventRepository = riskEventRepository;
        this.dlqCounter = Counter.builder("kafka.dlq.received")
                .description("Messages received in DLQ")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_DLQ,
            groupId = "fraud-dlq-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleDlq(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String errorMessage,
            @Header(value = "kafka_original-topic", required = false) String originalTopic,
            Acknowledgment ack) {

        MDC.put("payment_id", event.getPaymentId());
        MDC.put("dlq", "true");

        try {
            log.error("╔══════════════════════════════════════════════════════════════╗");
            log.error("║                    DLQ MESSAGE RECEIVED                      ║");
            log.error("╠══════════════════════════════════════════════════════════════╣");
            log.error("║ Payment ID: {}", event.getPaymentId());
            log.error("║ Original Topic: {}", originalTopic);
            log.error("║ Error: {}", errorMessage);
            log.error("║ Retry Count: {}", event.getRetryCount());
            log.error("║ Last Error: {}", event.getLastError());
            log.error("╚══════════════════════════════════════════════════════════════╝");

            // 1. Store in database for manual review
            saveDlqEvent(event, errorMessage, originalTopic);

            // 2. Update metrics
            dlqCounter.increment();

            // 3. Acknowledge
            ack.acknowledge();

            log.info("DLQ message processed and stored for manual review");

        } catch (Exception e) {
            log.error("Failed to process DLQ message: {}", e.getMessage(), e);
            // Still acknowledge to prevent infinite loop
            ack.acknowledge();
        } finally {
            MDC.clear();
        }
    }

    private void saveDlqEvent(PaymentEvent event, String errorMessage, String originalTopic) {
        RiskEvent dlqEvent = RiskEvent.failed(
                event.getPaymentId(),
                PipelineStep.DECISION,  // Use DECISION as final step
                buildErrorDetail(event, errorMessage, originalTopic)
        );
        
        // Override with DLQ-specific details
        dlqEvent.setDetail(Map.of(
                "dlq", true,
                "original_topic", originalTopic != null ? originalTopic : "unknown",
                "error_message", errorMessage != null ? errorMessage : "unknown",
                "retry_count", event.getRetryCount(),
                "last_error", event.getLastError() != null ? event.getLastError() : "unknown",
                "payment_state", event.getState() != null ? event.getState().name() : "unknown",
                "risk_score", event.getRiskScore() != null ? event.getRiskScore().toString() : "null"
        ));
        
        riskEventRepository.save(dlqEvent);
    }

    private String buildErrorDetail(PaymentEvent event, String errorMessage, String originalTopic) {
        return String.format(
                "DLQ: payment=%s, topic=%s, retries=%d, error=%s",
                event.getPaymentId(),
                originalTopic,
                event.getRetryCount(),
                errorMessage
        );
    }
}
