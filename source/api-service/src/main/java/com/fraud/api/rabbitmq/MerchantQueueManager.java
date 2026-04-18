package com.fraud.api.rabbitmq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic queue creation for per-merchant routing.
 * 
 * Each merchant gets its own queue:
 * - Queue name: merchant.{merchantId}
 * - Binding: risk.direct exchange with routing key = merchantId
 * - Properties: TTL, max-length, DLX
 */
@Component
@Slf4j
public class MerchantQueueManager {

    private final RabbitAdmin rabbitAdmin;
    private final DirectExchange riskDirectExchange;
    private final RabbitMQConfig rabbitMQConfig;
    
    // Cache of created queues to avoid redundant declarations
    private final ConcurrentHashMap<String, Boolean> createdQueues = new ConcurrentHashMap<>();

    public MerchantQueueManager(
            RabbitAdmin rabbitAdmin,
            DirectExchange riskDirectExchange,
            RabbitMQConfig rabbitMQConfig) {
        this.rabbitAdmin = rabbitAdmin;
        this.riskDirectExchange = riskDirectExchange;
        this.rabbitMQConfig = rabbitMQConfig;
    }

    /**
     * Ensure queue exists for merchant.
     * Creates queue and binding if not already created.
     * 
     * @param merchantId The merchant ID
     * @return Queue name
     */
    public String ensureQueueExists(String merchantId) {
        String queueName = getQueueName(merchantId);
        
        // Check cache first
        if (createdQueues.containsKey(merchantId)) {
            return queueName;
        }
        
        // Create queue (idempotent operation)
        createMerchantQueue(merchantId, queueName);
        
        return queueName;
    }

    /**
     * Get queue name for merchant.
     */
    public String getQueueName(String merchantId) {
        return RabbitMQConfig.QUEUE_PREFIX + merchantId;
    }

    /**
     * Create queue and binding for merchant.
     */
    private synchronized void createMerchantQueue(String merchantId, String queueName) {
        // Double-check after acquiring lock
        if (createdQueues.containsKey(merchantId)) {
            return;
        }

        try {
            // Build queue with arguments
            Map<String, Object> args = rabbitMQConfig.getMerchantQueueArguments();
            Queue queue = QueueBuilder.durable(queueName)
                    .withArguments(args)
                    .build();

            // Declare queue
            rabbitAdmin.declareQueue(queue);

            // Bind to exchange with merchantId as routing key
            rabbitAdmin.declareBinding(
                    BindingBuilder.bind(queue)
                            .to(riskDirectExchange)
                            .with(merchantId)
            );

            // Cache
            createdQueues.put(merchantId, true);

            log.info("Created queue for merchant: {} -> {}", merchantId, queueName);

        } catch (Exception e) {
            log.error("Failed to create queue for merchant {}: {}", merchantId, e.getMessage(), e);
            throw new RuntimeException("Failed to create merchant queue", e);
        }
    }

    /**
     * Check if queue exists for merchant.
     */
    public boolean queueExists(String merchantId) {
        return createdQueues.containsKey(merchantId);
    }

    /**
     * Get all created merchant IDs.
     */
    public java.util.Set<String> getCreatedMerchants() {
        return createdQueues.keySet();
    }
}
