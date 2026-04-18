# Domain Model — Fraud Detection Gateway

## 1. Core Entities

### 1.1 Payment

```java
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    private String paymentId;
    
    private String merchantId;
    private Long amount;
    private String currency;
    private String cardBin;
    private String ip;
    private String deviceId;
    private String idempotencyKey;
    
    @Enumerated(EnumType.STRING)
    private PaymentState state;
    
    @Enumerated(EnumType.STRING)
    private Decision decision;
    
    private BigDecimal riskScore;
    
    private Instant createdAt;
    private Instant updatedAt;
    private Instant decidedAt;
}
```

### 1.2 RiskEvent

```java
@Entity
@Table(name = "risk_events")
public class RiskEvent {
    @Id
    @GeneratedValue
    private Long id;
    
    private String paymentId;
    
    @Enumerated(EnumType.STRING)
    private PipelineStep step;
    
    @Enumerated(EnumType.STRING)
    private StepStatus status;
    
    @Type(JsonType.class)
    private Map<String, Object> details;
    
    private Instant createdAt;
}
```

---

## 2. Enums

### 2.1 PaymentState

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      PAYMENT STATE MACHINE                              │
└─────────────────────────────────────────────────────────────────────────┘

                              ┌─────────┐
                              │ PENDING │
                              └────┬────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
              ▼                    ▼                    ▼
        ┌─────────┐          ┌─────────┐          ┌─────────┐
        │ DECIDED │          │ REVIEW  │          │ BLOCKED │
        │ (ALLOW) │          │         │          │ (BLOCK) │
        └─────────┘          └────┬────┘          └─────────┘
                                  │
                    ┌─────────────┼─────────────┐
                    │                           │
                    ▼                           ▼
              ┌─────────┐                 ┌─────────┐
              │ DECIDED │                 │ BLOCKED │
              │ (human) │                 │ (human) │
              └─────────┘                 └─────────┘
```

```java
public enum PaymentState {
    PENDING,    // Initial state
    DECIDED,    // Auto-approved (ALLOW)
    REVIEW,     // Needs human review
    BLOCKED     // Auto-blocked or human-rejected
}
```

### 2.2 Decision

```java
public enum Decision {
    ALLOW,   // Payment approved
    REVIEW,  // Needs human review
    BLOCK    // Payment blocked
}
```

### 2.3 PipelineStep

```java
public enum PipelineStep {
    INGEST,    // Validate, normalize
    FEATURE,   // Extract features
    MODEL,     // Calculate risk score
    RULE,      // Apply business rules
    DECISION   // Final decision
}
```

### 2.4 StepStatus

```java
public enum StepStatus {
    STARTED,   // Step started
    RUNNING,   // Step in progress
    DONE,      // Step completed successfully
    FAILED,    // Step failed
    RETRIED,   // Step retried
    SKIPPED    // Step skipped (idempotency)
}
```

---

## 3. Database Schema

### 3.1 payments table

```sql
CREATE TABLE payments (
    payment_id      VARCHAR(64) PRIMARY KEY,
    merchant_id     VARCHAR(64) NOT NULL,
    amount          BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    card_bin        VARCHAR(6),
    ip              VARCHAR(45),
    device_id       VARCHAR(128),
    idempotency_key VARCHAR(128) NOT NULL,
    state           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decision        VARCHAR(20),
    risk_score      DECIMAL(5,4),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at      TIMESTAMP,
    
    CONSTRAINT uk_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_payments_merchant ON payments(merchant_id);
CREATE INDEX idx_payments_state ON payments(state);
CREATE INDEX idx_payments_created ON payments(created_at);
```

### 3.2 risk_events table

```sql
CREATE TABLE risk_events (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  VARCHAR(64) NOT NULL,
    step        VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    details     JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_payment_step UNIQUE (payment_id, step, status)
);

CREATE INDEX idx_risk_events_payment ON risk_events(payment_id);
CREATE INDEX idx_risk_events_step ON risk_events(step);
```

### 3.3 feature_cache table

```sql
CREATE TABLE feature_cache (
    cache_key   VARCHAR(256) PRIMARY KEY,
    features    JSONB NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP NOT NULL
);

CREATE INDEX idx_feature_cache_expires ON feature_cache(expires_at);
```

---

## 4. DTOs

### 4.1 AuthorizeRequest

```java
public record AuthorizeRequest(
    @NotBlank String paymentId,
    @NotBlank String merchantId,
    @Positive Long amount,
    @NotBlank String currency,
    String cardBin,
    String ip,
    String deviceId,
    @NotBlank String idempotencyKey
) {}
```

### 4.2 AuthorizeResponse

```java
public record AuthorizeResponse(
    String paymentId,
    Decision decision,
    BigDecimal riskScore,
    PaymentState state,
    Instant processedAt
) {}
```

### 4.3 PaymentEvent (for Kafka/RabbitMQ)

```java
public class PaymentEvent {
    private String paymentId;
    private String merchantId;
    private Long amount;
    private String currency;
    private String cardBin;
    private String ip;
    private String deviceId;
    private String idempotencyKey;
    
    // Pipeline state
    private Instant ingestedAt;
    private Map<String, Object> features;
    private BigDecimal riskScore;
    private Map<String, Boolean> ruleResults;
    private Decision decision;
    private PaymentState state;
    
    // Retry metadata
    private int retryCount;
    private String lastError;
}
```

---

## 5. Repositories

### 5.1 PaymentRepository

```java
public interface PaymentRepository extends JpaRepository<Payment, String> {
    
    Optional<Payment> findByMerchantIdAndIdempotencyKey(
            String merchantId, String idempotencyKey);
    
    List<Payment> findByStateOrderByCreatedAtDesc(PaymentState state);
    
    @Query("SELECT p FROM Payment p WHERE p.state = :state AND p.createdAt > :since")
    List<Payment> findRecentByState(
            @Param("state") PaymentState state, 
            @Param("since") Instant since);
}
```

### 5.2 RiskEventRepository

```java
public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {
    
    List<RiskEvent> findByPaymentIdOrderByCreatedAtAsc(String paymentId);
    
    boolean existsByPaymentIdAndStepAndStatus(
            String paymentId, PipelineStep step, StepStatus status);
    
    @Query("SELECT COUNT(e) FROM RiskEvent e WHERE e.step = :step AND e.status = :status")
    long countByStepAndStatus(
            @Param("step") PipelineStep step, 
            @Param("status") StepStatus status);
}
```

---

## 6. Validation Rules

### 6.1 Business Rules

| Rule | Condition | Action |
|------|-----------|--------|
| Blacklisted BIN | `cardBin IN ('000000', '111111', '999999')` | BLOCK |
| High-risk country | `ipCountry IN ('RU', 'NG', 'PK')` | +0.3 score |
| Proxy IP | `isProxy = true` | +0.25 score |
| High velocity | `deviceTxCount24h > 15` | REVIEW |
| Large amount | `amountPercentile > 0.95` | REVIEW |

### 6.2 Decision Thresholds

| Risk Score | Decision |
|------------|----------|
| < 0.4 | ALLOW |
| 0.4 - 0.7 | REVIEW |
| > 0.7 | BLOCK |

---

## 7. State Transitions

### 7.1 Valid Transitions

```java
public class PaymentStateMachine {
    
    private static final Map<PaymentState, Set<PaymentState>> VALID_TRANSITIONS = Map.of(
        PENDING, Set.of(DECIDED, REVIEW, BLOCKED),
        REVIEW, Set.of(DECIDED, BLOCKED)
    );
    
    public void transition(Payment payment, PaymentState newState) {
        PaymentState current = payment.getState();
        
        if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(newState)) {
            throw new InvalidStateTransitionException(current, newState);
        }
        
        payment.setState(newState);
        payment.setUpdatedAt(Instant.now());
        
        if (newState == DECIDED || newState == BLOCKED) {
            payment.setDecidedAt(Instant.now());
        }
    }
}
```

### 7.2 Invalid Transitions

| From | To | Reason |
|------|----|--------|
| DECIDED | * | Final state |
| BLOCKED | * | Final state |
| REVIEW | PENDING | Cannot go back |
