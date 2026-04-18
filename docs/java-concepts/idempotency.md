# Idempotency — Lý thuyết chuyên sâu

## Chương 1: Định nghĩa và Tầm quan trọng

### 1.1 Idempotency là gì?

**Idempotency** (tính lũy đẳng) là tính chất của một operation mà khi thực hiện nhiều lần với cùng input, kết quả vẫn giống như thực hiện một lần.

```
Mathematically:
f(f(x)) = f(x)

In practice:
processPayment(p-123) lần 1 → Payment created
processPayment(p-123) lần 2 → Same payment returned (no duplicate)
processPayment(p-123) lần 3 → Same payment returned (no duplicate)
```

### 1.2 Tại sao Idempotency quan trọng?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DISTRIBUTED SYSTEMS FAILURES                        │
└─────────────────────────────────────────────────────────────────────────┘

    Scenario 1: Network Timeout
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Client ──────▶ Server ──────▶ Process ──────▶ Response            │
    │    │              │              ✓              │                  │
    │    │              │                             │                  │
    │    │              │                        TIMEOUT!                │
    │    │              │                             │                  │
    │    │              │                             ✗                  │
    │    │                                                               │
    │    └── Client không biết request đã thành công hay chưa           │
    │        → Retry → Duplicate!                                        │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
    
    Scenario 2: Load Balancer Retry
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Client ──▶ LB ──▶ Server 1 ──▶ Process ──▶ Response               │
    │              │         │           ✓           │                   │
    │              │         │                  TIMEOUT!                 │
    │              │         │                       │                   │
    │              │         └───────────────────────┘                   │
    │              │                                                     │
    │              └──▶ Server 2 ──▶ Process AGAIN! (duplicate)          │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
    
    Scenario 3: Kafka Consumer Rebalance
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Consumer A: poll() → process ✓ → REBALANCE!                      │
    │                                      │                             │
    │                                      │ Partition reassigned        │
    │                                      ▼                             │
    │  Consumer B: poll() → process AGAIN! (duplicate)                   │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

### 1.3 Hậu quả của Duplicate Processing

| Scenario | Hậu quả |
|----------|---------|
| Payment | Charge customer twice |
| Inventory | Deduct stock twice |
| Email | Send notification twice |
| Order | Create duplicate orders |
| Transfer | Move money twice |

---

## Chương 2: Idempotency Strategies

### 2.1 Idempotency Key

Client gửi unique key với mỗi request:

```java
// Client side
POST /payments/authorize
{
    "idempotency_key": "uuid-12345-67890",  // Client generates
    "payment_id": "p-123",
    "amount": 100000
}

// Server side
@Transactional
public Payment authorize(AuthorizeRequest request) {
    // Check if already processed
    Optional<Payment> existing = paymentRepository
            .findByIdempotencyKey(request.getIdempotencyKey());
    
    if (existing.isPresent()) {
        log.info("Returning cached result for idempotency_key: {}", 
                request.getIdempotencyKey());
        return existing.get();  // Return same result
    }
    
    // Process new request
    Payment payment = processPayment(request);
    payment.setIdempotencyKey(request.getIdempotencyKey());
    return paymentRepository.save(payment);
}
```

### 2.2 Natural Key

Sử dụng business key tự nhiên:

```java
// Natural key: (merchant_id, order_id)
@Table(uniqueConstraints = {
    @UniqueConstraint(columnNames = {"merchant_id", "order_id"})
})
public class Payment {
    private String merchantId;
    private String orderId;  // Unique per merchant
}

// Check duplicate
Optional<Payment> existing = paymentRepository
        .findByMerchantIdAndOrderId(merchantId, orderId);
```

### 2.3 Database Constraint

Sử dụng UNIQUE constraint:

```sql
-- Table definition
CREATE TABLE payments (
    id SERIAL PRIMARY KEY,
    payment_id VARCHAR(255) NOT NULL,
    merchant_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    ...
    CONSTRAINT uk_idempotency UNIQUE (merchant_id, idempotency_key)
);

-- Insert will fail on duplicate
INSERT INTO payments (merchant_id, idempotency_key, ...)
VALUES ('m-123', 'key-456', ...);
-- ERROR: duplicate key value violates unique constraint "uk_idempotency"
```

### 2.4 Comparison

| Strategy | Pros | Cons |
|----------|------|------|
| Idempotency Key | Flexible, client-controlled | Client must generate key |
| Natural Key | No extra field | Not always available |
| DB Constraint | Guaranteed by DB | Requires error handling |
| Distributed Lock | Works across services | Complex, performance |

---

## Chương 3: Idempotency trong Multi-Step Pipeline

### 3.1 Vấn đề

Trong pipeline nhiều bước, mỗi bước có thể fail và retry:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    MULTI-STEP PIPELINE                                 │
└─────────────────────────────────────────────────────────────────────────┘

    Payment p-123:
    
    INGEST ──▶ FEATURE ──▶ MODEL ──▶ RULE ──▶ DECISION
       ✓          ✓          ✓        ✗
                                      │
                                   RETRY!
                                      │
                                      ▼
    INGEST ──▶ FEATURE ──▶ MODEL ──▶ RULE ──▶ DECISION
       ?          ?          ?        ✓         ✓
    
    Câu hỏi: Các bước INGEST, FEATURE, MODEL có chạy lại không?
    
    Nếu không có idempotency per step:
    - INGEST chạy lại → duplicate risk event
    - FEATURE chạy lại → wasted computation
    - MODEL chạy lại → wasted CPU
```

### 3.2 Giải pháp: Idempotency Per Step

```java
// Composite key: (payment_id, step)
@Table(uniqueConstraints = {
    @UniqueConstraint(columnNames = {"payment_id", "step", "status"})
})
public class RiskEvent {
    private String paymentId;
    private PipelineStep step;  // INGEST, FEATURE, MODEL, RULE, DECISION
    private StepStatus status;  // STARTED, DONE, FAILED
}

// Check before processing
public boolean isStepCompleted(String paymentId, PipelineStep step) {
    return riskEventRepository.existsByPaymentIdAndStepAndStatus(
            paymentId, step, StepStatus.DONE);
}

// In consumer
@KafkaListener(topics = "risk.feature")
public void consume(PaymentEvent event) {
    // Idempotency check
    if (isStepCompleted(event.getPaymentId(), PipelineStep.FEATURE)) {
        log.info("Step FEATURE already completed for {}, skipping", 
                event.getPaymentId());
        ack.acknowledge();
        return;
    }
    
    // Process
    processFeature(event);
    
    // Mark as done
    saveRiskEvent(event.getPaymentId(), PipelineStep.FEATURE, StepStatus.DONE);
}
```

### 3.3 Database Schema

```sql
CREATE TABLE risk_events (
    id SERIAL PRIMARY KEY,
    payment_id VARCHAR(255) NOT NULL,
    step VARCHAR(50) NOT NULL,      -- INGEST, FEATURE, MODEL, RULE, DECISION
    status VARCHAR(50) NOT NULL,    -- STARTED, DONE, FAILED
    detail JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite unique constraint for idempotency
    CONSTRAINT uk_payment_step_done UNIQUE (payment_id, step, status)
        WHERE status = 'DONE'
);

-- Index for fast lookup
CREATE INDEX idx_risk_events_payment_step 
    ON risk_events(payment_id, step, status);
```

### 3.4 Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    IDEMPOTENT STEP PROCESSING                          │
└─────────────────────────────────────────────────────────────────────────┘

    Message arrives
         │
         ▼
    ┌─────────────────┐
    │ Check: EXISTS   │
    │ (payment_id,    │
    │  step, DONE)?   │
    └────────┬────────┘
             │
        ┌────┴────┐
        │         │
       YES       NO
        │         │
        ▼         ▼
    ┌─────────┐  ┌─────────────────┐
    │  SKIP   │  │ Save STARTED    │
    │  ACK    │  └────────┬────────┘
    └─────────┘           │
                          ▼
                  ┌─────────────────┐
                  │    Process      │
                  └────────┬────────┘
                           │
                      Success?
                           │
                     ┌─────┴─────┐
                     │           │
                    YES         NO
                     │           │
                     ▼           ▼
              ┌───────────┐ ┌───────────┐
              │Save DONE  │ │Save FAILED│
              │    ACK    │ │   THROW   │
              └───────────┘ └───────────┘
```

---

## Chương 4: Implementation Patterns

### 4.1 Optimistic Approach

```java
@Transactional
public void processIdempotently(PaymentEvent event, PipelineStep step) {
    String paymentId = event.getPaymentId();
    
    // 1. Check if done
    if (riskEventRepository.existsByPaymentIdAndStepAndStatus(
            paymentId, step, StepStatus.DONE)) {
        log.info("Already done, skipping");
        return;
    }
    
    // 2. Process
    doProcess(event);
    
    // 3. Mark as done (may fail on duplicate)
    try {
        riskEventRepository.save(RiskEvent.done(paymentId, step, Map.of()));
    } catch (DataIntegrityViolationException e) {
        // Concurrent duplicate - already processed
        log.info("Concurrent processing detected, skipping");
    }
}
```

### 4.2 Pessimistic Approach (với Lock)

```java
@Transactional
public void processWithLock(PaymentEvent event, PipelineStep step) {
    String paymentId = event.getPaymentId();
    
    // 1. Acquire lock
    String lockKey = "process:" + paymentId + ":" + step;
    boolean acquired = redisLock.tryLock(lockKey, 30, TimeUnit.SECONDS);
    
    if (!acquired) {
        throw new ConcurrentProcessingException("Could not acquire lock");
    }
    
    try {
        // 2. Check if done (inside lock)
        if (isStepCompleted(paymentId, step)) {
            return;
        }
        
        // 3. Process
        doProcess(event);
        
        // 4. Mark as done
        saveRiskEvent(paymentId, step, StepStatus.DONE);
        
    } finally {
        redisLock.unlock(lockKey);
    }
}
```

### 4.3 Idempotency với Kafka Transactions

```java
@Transactional
public void processWithKafkaTransaction(PaymentEvent event) {
    // Kafka transaction ensures exactly-once:
    // - Consume from input topic
    // - Process
    // - Produce to output topic
    // - Commit offset
    // All happen atomically
    
    kafkaTemplate.executeInTransaction(operations -> {
        // Process
        PaymentEvent result = process(event);
        
        // Produce to next topic
        operations.send("risk.feature", event.getPaymentId(), result);
        
        return result;
    });
}
```

---

## Chương 5: Trong Fraud Detection Gateway

### 5.1 Repository Method

```java
public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {
    
    boolean existsByPaymentIdAndStepAndStatus(
            String paymentId, 
            PipelineStep step, 
            StepStatus status);
    
    Optional<RiskEvent> findByPaymentIdAndStepAndStatus(
            String paymentId,
            PipelineStep step,
            StepStatus status);
}
```

### 5.2 Consumer Implementation

```java
@Component
@Slf4j
public class FeatureConsumer {
    
    @KafkaListener(topics = "risk.feature")
    public void consume(PaymentEvent event, Acknowledgment ack) {
        String paymentId = event.getPaymentId();
        
        // 1. Idempotency check
        if (riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.FEATURE, StepStatus.DONE)) {
            log.info("FEATURE step already completed for {}", paymentId);
            ack.acknowledge();
            return;
        }
        
        // 2. Process
        Map<String, Object> features = extractFeatures(event);
        event.withFeatures(features);
        
        // 3. Mark as done
        riskEventRepository.save(
                RiskEvent.done(paymentId, PipelineStep.FEATURE, features));
        
        // 4. Forward to next topic
        kafkaTemplate.send("risk.model", paymentId, event);
        
        // 5. Acknowledge
        ack.acknowledge();
    }
}
```

### 5.3 Testing Idempotency

```java
@Test
void shouldBeIdempotent() {
    PaymentEvent event = createTestEvent("p-123");
    
    // Process first time
    consumer.consume(event, mockAck);
    
    // Verify processed
    assertTrue(riskEventRepository.existsByPaymentIdAndStepAndStatus(
            "p-123", PipelineStep.FEATURE, StepStatus.DONE));
    
    // Process second time (duplicate)
    consumer.consume(event, mockAck);
    
    // Verify only one record
    assertEquals(1, riskEventRepository.countByPaymentIdAndStep(
            "p-123", PipelineStep.FEATURE));
}
```

---

## Chương 6: Best Practices

### 6.1 Idempotency Key Guidelines

```
1. UNIQUE: Key phải unique cho mỗi logical operation
2. DETERMINISTIC: Same input → same key
3. STABLE: Key không đổi khi retry
4. MEANINGFUL: Có thể debug được

Good:
- UUID generated by client: "550e8400-e29b-41d4-a716-446655440000"
- Business key: "merchant-123:order-456"
- Hash of request: "sha256(request_body)"

Bad:
- Timestamp: "2024-01-15T10:30:00" (may change on retry)
- Random: Math.random() (different each time)
- Server-generated: (client doesn't know)
```

### 6.2 TTL for Idempotency Records

```java
// Don't keep forever - use TTL
@Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
public void cleanupOldIdempotencyRecords() {
    Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
    riskEventRepository.deleteByCreatedAtBefore(cutoff);
}
```

### 6.3 Monitoring

```java
// Track duplicate rate
Counter duplicateCounter = Counter.builder("idempotency.duplicate")
    .tag("step", step.name())
    .register(registry);

if (isAlreadyProcessed) {
    duplicateCounter.increment();
}

// Alert if duplicate rate > 5%
// May indicate client retry issues
```

---

## Tham khảo

- [Idempotency Patterns](https://microservices.io/patterns/communication-style/idempotent-consumer.html)
- [Stripe Idempotency](https://stripe.com/docs/api/idempotent_requests)
- [AWS Idempotency](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)
- [Designing Data-Intensive Applications](https://dataintensive.net/) — Chapter 11
