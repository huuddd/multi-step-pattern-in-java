package com.fraud.api.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic configuration for the fraud detection pipeline.
 * 
 * Topic naming convention: risk.<stage>
 * - risk.ingest: Raw payment events from API
 * - risk.feature: Enriched with features
 * - risk.model: Scored with risk score
 * - risk.rule: Rule evaluation results
 * - risk.dlq: Dead letter queue for failed messages
 */
@Configuration
public class KafkaTopicConfig {

    public static final String TOPIC_INGEST = "risk.ingest";
    public static final String TOPIC_FEATURE = "risk.feature";
    public static final String TOPIC_MODEL = "risk.model";
    public static final String TOPIC_RULE = "risk.rule";
    public static final String TOPIC_DLQ = "risk.dlq";

    @Value("${kafka.topic.partitions:6}")
    private int partitions;

    @Value("${kafka.topic.replication-factor:1}")
    private short replicationFactor;

    /**
     * Ingest topic: receives raw payment events from API.
     * Partitioned by payment_id for ordering guarantee.
     */
    @Bean
    public NewTopic ingestTopic() {
        return TopicBuilder.name(TOPIC_INGEST)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000")  // 7 days
                .config("cleanup.policy", "delete")
                .build();
    }

    /**
     * Feature topic: contains events enriched with extracted features.
     */
    @Bean
    public NewTopic featureTopic() {
        return TopicBuilder.name(TOPIC_FEATURE)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000")
                .build();
    }

    /**
     * Model topic: contains events with risk scores.
     */
    @Bean
    public NewTopic modelTopic() {
        return TopicBuilder.name(TOPIC_MODEL)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000")
                .build();
    }

    /**
     * Rule topic: contains events with rule evaluation results.
     */
    @Bean
    public NewTopic ruleTopic() {
        return TopicBuilder.name(TOPIC_RULE)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000")
                .build();
    }

    /**
     * Dead Letter Queue: receives messages that failed processing after max retries.
     * Single partition since DLQ is for manual inspection, not high throughput.
     */
    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(TOPIC_DLQ)
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", "2592000000")  // 30 days
                .build();
    }
}
