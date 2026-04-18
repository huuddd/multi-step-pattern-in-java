package com.fraud.api.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for per-merchant routing.
 * 
 * Architecture:
 * - Direct Exchange: risk.direct
 * - Queues: merchant.{merchantId} (created dynamically)
 * - Routing Key: merchantId
 * - DLX: risk.dlx for failed messages
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_RISK_DIRECT = "risk.direct";
    public static final String EXCHANGE_DLX = "risk.dlx";
    public static final String QUEUE_DLQ = "risk.dlq";
    public static final String QUEUE_PREFIX = "merchant.";

    @Value("${rabbitmq.consumer.prefetch-count:1}")
    private int prefetchCount;

    @Value("${rabbitmq.queue.message-ttl:300000}")
    private int messageTtl;

    @Value("${rabbitmq.queue.max-length:10000}")
    private int maxLength;

    /**
     * Direct exchange for per-merchant routing.
     * Messages are routed based on exact match of routing key (merchantId).
     */
    @Bean
    public DirectExchange riskDirectExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_RISK_DIRECT)
                .durable(true)
                .build();
    }

    /**
     * Dead Letter Exchange for failed messages.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_DLX)
                .durable(true)
                .build();
    }

    /**
     * Dead Letter Queue.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DLQ)
                .build();
    }

    /**
     * Bind DLQ to DLX.
     */
    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("dlq");
    }

    /**
     * RabbitAdmin for dynamic queue creation.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    /**
     * JSON message converter.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate with JSON converter.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setExchange(EXCHANGE_RISK_DIRECT);
        return template;
    }

    /**
     * Listener container factory with manual ack and prefetch=1.
     * prefetch=1 ensures fair dispatch - each consumer gets one message at a time.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        
        // Manual acknowledgment
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        
        // Prefetch count = 1 for fair dispatch
        factory.setPrefetchCount(prefetchCount);
        
        // Concurrency
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        
        return factory;
    }

    /**
     * Get queue arguments for merchant queues.
     */
    public java.util.Map<String, Object> getMerchantQueueArguments() {
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("x-message-ttl", messageTtl);
        args.put("x-max-length", maxLength);
        args.put("x-dead-letter-exchange", EXCHANGE_DLX);
        args.put("x-dead-letter-routing-key", "dlq");
        return args;
    }
}
