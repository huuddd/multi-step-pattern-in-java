package com.fraud.api.rabbitmq;

import com.fraud.common.event.PaymentEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publisher for per-merchant routing.
 * 
 * Routes messages to merchant-specific queues using:
 * - Exchange: risk.direct
 * - Routing Key: merchantId
 * 
 * This ensures:
 * - Isolation between merchants
 * - Fair processing (no noisy neighbor)
 * - Independent scaling per merchant
 */
@Component
@Slf4j
public class MerchantMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MerchantQueueManager queueManager;
    private final Counter publishedCounter;

    public MerchantMessagePublisher(
            RabbitTemplate rabbitTemplate,
            MerchantQueueManager queueManager,
            MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.queueManager = queueManager;
        this.publishedCounter = Counter.builder("rabbitmq.messages.published")
                .description("Messages published to merchant queues")
                .register(meterRegistry);
    }

    /**
     * Publish payment event to merchant-specific queue.
     * 
     * @param event The payment event to publish
     */
    public void publish(PaymentEvent event) {
        String merchantId = event.getMerchantId();
        
        // Ensure queue exists for this merchant
        queueManager.ensureQueueExists(merchantId);
        
        // Publish with merchantId as routing key
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_RISK_DIRECT,
                merchantId,  // routing key
                event
        );
        
        publishedCounter.increment();
        
        log.debug("Published event to merchant queue: merchantId={}, paymentId={}",
                merchantId, event.getPaymentId());
    }

    /**
     * Publish payment event with custom routing key.
     * 
     * @param event The payment event
     * @param routingKey Custom routing key
     */
    public void publish(PaymentEvent event, String routingKey) {
        // Ensure queue exists
        queueManager.ensureQueueExists(routingKey);
        
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_RISK_DIRECT,
                routingKey,
                event
        );
        
        publishedCounter.increment();
        
        log.debug("Published event with routing key: routingKey={}, paymentId={}",
                routingKey, event.getPaymentId());
    }

    /**
     * Publish to DLQ directly (for manual DLQ routing).
     */
    public void publishToDlq(PaymentEvent event, String reason) {
        event.incrementRetry(reason);
        
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_DLX,
                "dlq",
                event
        );
        
        log.warn("Published event to DLQ: paymentId={}, reason={}",
                event.getPaymentId(), reason);
    }
}
