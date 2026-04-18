package com.fraud.api.domain;

import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "card_bin")
    private String cardBin;

    private String ip;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentState state = PaymentState.PENDING;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(name = "risk_score", precision = 5, scale = 4)
    private BigDecimal riskScore;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Transition to a new state with validation.
     * @throws IllegalStateException if transition is not allowed
     */
    public void transitionTo(PaymentState newState) {
        if (!state.canTransitionTo(newState)) {
            throw new IllegalStateException(
                String.format("Cannot transition from %s to %s", state, newState)
            );
        }
        this.state = newState;
    }

    /**
     * Complete the payment with a decision.
     */
    public void complete(Decision decision, BigDecimal riskScore) {
        this.decision = decision;
        this.riskScore = riskScore;
        
        PaymentState targetState = switch (decision) {
            case ALLOW -> PaymentState.DECIDED;
            case REVIEW -> PaymentState.REVIEW;
            case BLOCK -> PaymentState.BLOCKED;
        };
        transitionTo(targetState);
    }
}
