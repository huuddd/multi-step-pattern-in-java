# Idempotency Pattern — Fraud Detection Gateway

## 1. Tại sao cần Idempotency?

### 1.1 Vấn đề

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DUPLICATE REQUEST PROBLEM                           │
└─────────────────────────────────────────────────────────────────────────┘

    Client                    Server                    Database
      │                         │                          │
      │──── POST /authorize ───▶│                          │
      │                         │──── INSERT payment ─────▶│
      │                         │                          │
      │     (network timeout)   │                          │
      │                         │                          │
      │──── POST /authorize ───▶│  (retry)                 │
      │                         │──── INSERT payment ─────▶│ DUPLICATE!
      │                         │                          │
      
    Hậu quả:
    - Payment bị charge 2 lần
    - Customer bị trừ tiền 2 lần
    - Merchant nhận tiền 2 lần
```

### 1.2 Giải pháp: Idempotency Key

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    IDEMPOTENCY KEY SOLUTION                            │
└─────────────────────────────────────────────────────────────────────────┘

    Client                    Server                    Database
      │                         │                          │
      │── POST /authorize ─────▶│                          │
      │   Idempotency-Key: abc  │                          │
      │                         │──── INSERT payment ─────▶│
      │                         │     (idem_key: abc)      │
      │                         │                          │
      │     (network timeout)   │                          │
      │                         │                          │
      │── POST /authorize ─────▶│  (retry)                 │
      │   Idempotency-Key: abc  │                          │
      │                         │──── SELECT payment ─────▶│
      │                         │     WHERE idem_key=abc   │
      │                         │◀─── Found! ──────────────│
      │◀─── Return cached ──────│                          │
      │                         │                          │
      
    Kết quả:
    - Payment chỉ được xử lý 1 lần
    - Retry nhận được kết quả cached
```

---

## 2. Implementation

### 2.1 Database Constraint

```sql
CREATE TABLE payments (
    payment_id      VARCHAR(64) PRIMARY KEY,
    merchant_id     VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    -- ...
    
    CONSTRAINT uk_merchant_idempotency 
        UNIQUE (merchant_id, idempotency_key)
);
```

**Tại sao composite key `(merchant_id, idempotency_key)`?**
- Mỗi merchant có namespace riêng
- Merchant A có thể dùng key "order-123"
- Merchant B cũng có thể dùng key "order-123"
- Không conflict vì khác merchant

### 2.2 Service Layer

```java
@Service
@Transactional
public class PaymentService {
    
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        // 1. Check idempotency
        Optional<Payment> existing = paymentRepository
                .findByMerchantIdAndIdempotencyKey(
                        request.merchantId(), 
                        request.idempotencyKey());
        
        if (existing.isPresent()) {
            // Return cached result
            return mapToResponse(existing.get());
        }
        
        // 2. Process new payment
        Payment payment = createPayment(request);
        PipelineContext ctx = pipeline.execute(payment);
        
        // 3. Save and return
        payment = paymentRepository.save(payment);
        return mapToResponse(payment);
    }
}
```

### 2.3 Handling Race Conditions

```java
@Service
public class PaymentService {
    
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        try {
            return processPayment(request);
            
        } catch (DataIntegrityViolationException e) {
            // Race condition: another request inserted first
            // Fetch and return the existing result
            return paymentRepository
                    .findByMerchantIdAndIdempotencyKey(
                            request.merchantId(), 
                            request.idempotencyKey())
                    .map(this::mapToResponse)
                    .orElseThrow(() -> new IllegalStateException(
                            "Payment not found after constraint violation"));
        }
    }
}
```

---

## 3. Idempotency trong Multi-Step Pipeline

### 3.1 Per-Step Idempotency

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PER-STEP IDEMPOTENCY                                │
└─────────────────────────────────────────────────────────────────────────┘

    Mỗi step được track riêng:
    
    risk_events table:
    ┌────────────┬─────────┬────────┬─────────────────────────────────┐
    │ payment_id │  step   │ status │ details                         │
    ├────────────┼─────────┼────────┼─────────────────────────────────┤
    │ p-123      │ INGEST  │ DONE   │ {"validated": true}             │
    │ p-123      │ FEATURE │ DONE   │ {"features": {...}}             │
    │ p-123      │ MODEL   │ DONE   │ {"risk_score": 0.35}            │
    │ p-123      │ RULE    │ DONE   │ {"rules": {...}}                │
    │ p-123      │ DECISION│ DONE   │ {"decision": "ALLOW"}           │
    └────────────┴─────────┴────────┴─────────────────────────────────┘
    
    UNIQUE constraint: (payment_id, step, status)
```

### 3.2 Consumer Implementation

```java
@KafkaListener(topics = "risk.feature")
public void consume(PaymentEvent event, Acknowledgment ack) {
    String paymentId = event.getPaymentId();
    
    // 1. Check if already processed
    if (riskEventRepository.existsByPaymentIdAndStepAndStatus(
            paymentId, PipelineStep.FEATURE, StepStatus.DONE)) {
        log.info("Already processed, skipping: {}", paymentId);
        ack.acknowledge();
        return;
    }
    
    // 2. Process
    Map<String, Object> features = extractFeatures(event);
    event.withFeatures(features);
    
    // 3. Record completion
    riskEventRepository.save(RiskEvent.done(paymentId, PipelineStep.FEATURE, features));
    
    // 4. Forward to next stage
    kafkaTemplate.send("risk.model", event);
    
    // 5. Acknowledge
    ack.acknowledge();
}
```

---

## 4. Idempotency Key Best Practices

### 4.1 Key Generation

| Approach | Example | Pros | Cons |
|----------|---------|------|------|
| UUID | `550e8400-e29b-41d4-a716-446655440000` | Unique | No semantic meaning |
| Order ID | `order-12345` | Semantic | Requires unique order IDs |
| Composite | `order-12345-attempt-1` | Retry tracking | Complex |
| Hash | `sha256(order+amount+time)` | Deterministic | Collision risk |

### 4.2 Recommendations

```java
// Good: Use order ID if available
String idempotencyKey = "order-" + orderId;

// Good: Include retry attempt
String idempotencyKey = String.format("order-%s-attempt-%d", orderId, attempt);

// Good: UUID for no natural key
String idempotencyKey = UUID.randomUUID().toString();

// Bad: Timestamp-based (not deterministic)
String idempotencyKey = "payment-" + System.currentTimeMillis(); // DON'T
```

### 4.3 Key Expiration

```java
// Idempotency keys should expire after some time
// to allow legitimate retries with same key

@Scheduled(cron = "0 0 * * * *") // Every hour
public void cleanupExpiredKeys() {
    Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
    paymentRepository.deleteByCreatedAtBefore(cutoff);
}
```

---

## 5. Testing Idempotency

### 5.1 Unit Test

```java
@Test
void shouldReturnCachedResultForDuplicateRequest() {
    // Given
    AuthorizeRequest request = createRequest("idem-123");
    
    // When - First request
    AuthorizeResponse first = paymentService.authorize(request);
    
    // When - Duplicate request
    AuthorizeResponse second = paymentService.authorize(request);
    
    // Then
    assertThat(second.paymentId()).isEqualTo(first.paymentId());
    assertThat(second.decision()).isEqualTo(first.decision());
    
    // Verify only one payment created
    assertThat(paymentRepository.count()).isEqualTo(1);
}
```

### 5.2 Concurrent Test

```java
@Test
void shouldHandleConcurrentRequests() throws Exception {
    // Given
    AuthorizeRequest request = createRequest("idem-concurrent");
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // When - Submit concurrent requests
    List<Future<AuthorizeResponse>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
        futures.add(executor.submit(() -> {
            latch.countDown();
            latch.await(); // Start all at once
            return paymentService.authorize(request);
        }));
    }
    
    // Then - All should return same result
    Set<String> paymentIds = futures.stream()
            .map(f -> f.get().paymentId())
            .collect(Collectors.toSet());
    
    assertThat(paymentIds).hasSize(1);
    assertThat(paymentRepository.count()).isEqualTo(1);
}
```

---

## 6. Tham khảo

- [Stripe Idempotency](https://stripe.com/docs/api/idempotent_requests)
- [AWS Idempotency](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)
- [Martin Fowler - Idempotency](https://martinfowler.com/articles/patterns-of-distributed-systems/idempotent-receiver.html)
