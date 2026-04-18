package com.fraud.common.event;

import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Event representing a payment flowing through the Kafka pipeline.
 * Immutable after creation - each stage adds data without modifying existing fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {

    // Core payment data (set at ingest)
    private String paymentId;
    private String merchantId;
    private Long amount;
    private String currency;
    private String cardBin;
    private String ip;
    private String deviceId;
    private String idempotencyKey;
    
    // Timestamps
    private Instant createdAt;
    private Instant ingestedAt;
    private Instant featuredAt;
    private Instant scoredAt;
    private Instant decidedAt;
    
    // Feature data (set by feature stage)
    @Builder.Default
    private Map<String, Object> features = new HashMap<>();
    
    // Model data (set by model stage)
    private BigDecimal riskScore;
    
    // Rule data (set by rule stage)
    @Builder.Default
    private Map<String, Boolean> ruleResults = new HashMap<>();
    
    // Decision data (set by decision stage)
    private Decision decision;
    private PaymentState state;
    
    // Pipeline metadata
    private String correlationId;
    private int retryCount;
    private String lastError;
    
    /**
     * Create event from authorize request.
     */
    public static PaymentEvent fromRequest(
            String paymentId,
            String merchantId,
            Long amount,
            String currency,
            String cardBin,
            String ip,
            String deviceId,
            String idempotencyKey) {
        return PaymentEvent.builder()
                .paymentId(paymentId)
                .merchantId(merchantId)
                .amount(amount)
                .currency(currency)
                .cardBin(cardBin)
                .ip(ip)
                .deviceId(deviceId)
                .idempotencyKey(idempotencyKey)
                .createdAt(Instant.now())
                .correlationId(paymentId)
                .retryCount(0)
                .state(PaymentState.PENDING)
                .build();
    }
    
    /**
     * Mark as ingested.
     */
    public PaymentEvent markIngested() {
        this.ingestedAt = Instant.now();
        return this;
    }
    
    /**
     * Add features from feature extraction.
     */
    public PaymentEvent withFeatures(Map<String, Object> features) {
        this.features = new HashMap<>(features);
        this.featuredAt = Instant.now();
        return this;
    }
    
    /**
     * Add risk score from model.
     */
    public PaymentEvent withRiskScore(BigDecimal riskScore) {
        this.riskScore = riskScore;
        this.scoredAt = Instant.now();
        return this;
    }
    
    /**
     * Add rule results.
     */
    public PaymentEvent withRuleResults(Map<String, Boolean> ruleResults) {
        this.ruleResults = new HashMap<>(ruleResults);
        return this;
    }
    
    /**
     * Set final decision.
     */
    public PaymentEvent withDecision(Decision decision, PaymentState state) {
        this.decision = decision;
        this.state = state;
        this.decidedAt = Instant.now();
        return this;
    }
    
    /**
     * Increment retry count.
     */
    public PaymentEvent incrementRetry(String error) {
        this.retryCount++;
        this.lastError = error;
        return this;
    }
}
