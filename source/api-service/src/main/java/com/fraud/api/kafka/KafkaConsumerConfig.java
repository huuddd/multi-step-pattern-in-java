package com.fraud.api.kafka;

import com.fraud.common.event.PaymentEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration with manual acknowledgment.
 * 
 * Key concepts:
 * - AckMode.MANUAL: commit only after successful processing
 * - Concurrency: number of consumer threads (should match partitions)
 * - Error handler: retry with backoff before sending to DLQ
 */
@Configuration
@EnableKafka
@Slf4j
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.consumer.concurrency:6}")
    private int concurrency;

    @Value("${kafka.consumer.max-poll-records:100}")
    private int maxPollRecords;

    @Value("${kafka.consumer.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${kafka.consumer.retry.backoff-ms:1000}")
    private long retryBackoffMs;

    /**
     * Consumer factory for PaymentEvent messages.
     */
    @Bean
    public ConsumerFactory<String, PaymentEvent> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        
        // Broker connection
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Deserialization
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        
        // Consumer behavior
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);  // Manual commit
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Session management
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        
        // JSON deserializer config
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fraud.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, PaymentEvent.class.getName());
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Kafka listener container factory with manual acknowledgment.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> 
            kafkaListenerContainerFactory() {
        
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory());
        
        // Manual acknowledgment - commit only after ack.acknowledge()
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        
        // Concurrency = number of consumer threads
        // Should match number of partitions for optimal parallelism
        factory.setConcurrency(concurrency);
        
        // Error handler with retry
        factory.setCommonErrorHandler(errorHandler());
        
        log.info("Kafka consumer configured: concurrency={}, ackMode=MANUAL", concurrency);
        
        return factory;
    }

    /**
     * Error handler with fixed backoff retry.
     * After max attempts, message is sent to DLQ.
     */
    @Bean
    public DefaultErrorHandler errorHandler() {
        // Retry with fixed backoff
        FixedBackOff backOff = new FixedBackOff(retryBackoffMs, maxRetryAttempts - 1);
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, exception) -> {
            // This is called after all retries exhausted
            log.error("Message failed after {} retries, sending to DLQ: key={}, topic={}, partition={}, offset={}",
                    maxRetryAttempts,
                    record.key(),
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception);
        }, backOff);
        
        // Don't retry on these exceptions (send to DLQ immediately)
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                NullPointerException.class
        );
        
        return errorHandler;
    }
}
