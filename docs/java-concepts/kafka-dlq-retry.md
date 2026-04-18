# Kafka Dead Letter Queue và Retry — Lý thuyết chuyên sâu

## Chương 1: Tại sao cần DLQ?

### 1.1 Vấn đề với Message Processing

Trong hệ thống message-driven, có nhiều lý do khiến message không thể xử lý:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CÁC LOẠI LỖI KHI XỬ LÝ MESSAGE                      │
└─────────────────────────────────────────────────────────────────────────┘

    1. TRANSIENT ERRORS (Tạm thời)
    ┌────────────────────────────────────────────────────────────────────┐
    │  - Database connection timeout                                     │
    │  - Network hiccup                                                  │
    │  - External service temporarily unavailable                        │
    │  - Resource exhaustion (thread pool full)                          │
    │                                                                    │
    │  Giải pháp: RETRY sau một khoảng thời gian                        │
    └────────────────────────────────────────────────────────────────────┘
    
    2. PERMANENT ERRORS (Vĩnh viễn)
    ┌────────────────────────────────────────────────────────────────────┐
    │  - Invalid message format (JSON parse error)                       │
    │  - Business rule violation (amount < 0)                            │
    │  - Missing required field                                          │
    │  - Data integrity error                                            │
    │                                                                    │
    │  Giải pháp: Gửi vào DLQ, không retry                              │
    └────────────────────────────────────────────────────────────────────┘
    
    3. POISON MESSAGES
    ┌────────────────────────────────────────────────────────────────────┐
    │  - Message gây crash consumer                                      │
    │  - Infinite loop trong processing                                  │
    │  - Memory leak khi xử lý                                           │
    │                                                                    │
    │  Giải pháp: Detect và gửi vào DLQ ngay                            │
    └────────────────────────────────────────────────────────────────────┘
```

### 1.2 Nếu không có DLQ?

```
Scenario: Poison message trong topic

    ┌─────────┐
    │ Message │ ──▶ Consumer ──▶ CRASH!
    │ (poison)│
    └─────────┘
         │
         │ Restart consumer
         ▼
    ┌─────────┐
    │ Message │ ──▶ Consumer ──▶ CRASH! (lại)
    │ (poison)│
    └─────────┘
         │
         │ Restart consumer
         ▼
    ... vòng lặp vô hạn ...

    Hậu quả:
    - Consumer không thể tiến lên
    - Tất cả messages sau bị block
    - Lag tăng vô hạn
    - Alert storm
```

### 1.3 Giải pháp: Dead Letter Queue

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DEAD LETTER QUEUE PATTERN                           │
└─────────────────────────────────────────────────────────────────────────┘

    Main Topic                                                    DLQ
    ┌─────────┐                                              ┌─────────┐
    │ Message │ ──▶ Consumer ──▶ FAIL ──▶ Retry 1 ──▶ FAIL  │         │
    └─────────┘                              │               │  Dead   │
                                             ▼               │  Letter │
                                        Retry 2 ──▶ FAIL    │  Queue  │
                                             │               │         │
                                             ▼               │         │
                                        Retry 3 ──▶ FAIL ───▶│ Message │
                                                             └─────────┘
                                                                  │
                                                                  ▼
                                                         ┌───────────────┐
                                                         │ Manual Review │
                                                         │ Alert Team    │
                                                         │ Fix & Replay  │
                                                         └───────────────┘
```

---

## Chương 2: Retry Strategies

### 2.1 Blocking Retry (Đơn giản nhưng có vấn đề)

```java
// Blocking retry - consumer bị block
@KafkaListener(topics = "my-topic")
public void consume(Message message) {
    int retries = 0;
    while (retries < 3) {
        try {
            process(message);
            return;  // Success
        } catch (Exception e) {
            retries++;
            Thread.sleep(1000 * retries);  // BLOCKING!
        }
    }
    throw new RuntimeException("Max retries exceeded");
}
```

**Vấn đề:**
- Block consumer thread
- Tăng latency cho tất cả messages
- Có thể gây session timeout
- Không scale

### 2.2 Non-Blocking Retry (Recommended)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    NON-BLOCKING RETRY TOPOLOGY                         │
└─────────────────────────────────────────────────────────────────────────┘

    Main Topic          Retry Topic 1       Retry Topic 2        DLQ
    ┌─────────┐         ┌─────────┐         ┌─────────┐     ┌─────────┐
    │ my-topic│         │my-topic │         │my-topic │     │my-topic │
    │         │  FAIL   │-retry-0 │  FAIL   │-retry-1 │FAIL │  -dlt   │
    │         │ ──────▶ │         │ ──────▶ │         │────▶│         │
    └────┬────┘         └────┬────┘         └────┬────┘     └─────────┘
         │                   │                   │
         │              delay 1s            delay 5s
         │                   │                   │
         ▼                   ▼                   ▼
    ┌─────────┐         ┌─────────┐         ┌─────────┐
    │Consumer │         │Consumer │         │Consumer │
    │ Group 1 │         │ Group 1 │         │ Group 1 │
    └─────────┘         └─────────┘         └─────────┘

    Lợi ích:
    - Không block main consumer
    - Delay tự nhiên giữa các retry
    - Có thể scale retry consumers riêng
    - Visibility vào retry queue
```

### 2.3 Exponential Backoff

```java
// Delay tăng theo cấp số nhân
Attempt 1: delay = 1 second
Attempt 2: delay = 2 seconds  (1 * 2)
Attempt 3: delay = 4 seconds  (2 * 2)
Attempt 4: delay = 8 seconds  (4 * 2)
...

// Với multiplier và max delay
@Backoff(
    delay = 1000,        // Initial delay: 1s
    multiplier = 2,      // Multiply by 2 each retry
    maxDelay = 60000     // Cap at 60s
)
```

**Tại sao Exponential Backoff?**

```
Scenario: Database overloaded

    Linear Retry (1s, 1s, 1s):
    ┌────────────────────────────────────────────────────────────────────┐
    │  t=0s    t=1s    t=2s    t=3s    t=4s                             │
    │    │       │       │       │       │                               │
    │  retry  retry  retry  retry  retry  ──▶ DB vẫn overloaded!        │
    │                                                                    │
    │  Tất cả consumers retry cùng lúc → thundering herd                │
    └────────────────────────────────────────────────────────────────────┘
    
    Exponential Backoff (1s, 2s, 4s, 8s):
    ┌────────────────────────────────────────────────────────────────────┐
    │  t=0s    t=1s    t=3s    t=7s    t=15s                            │
    │    │       │       │       │        │                              │
    │  retry  retry  retry  retry   retry  ──▶ DB có thời gian recover  │
    │                                                                    │
    │  Spread out retries → giảm load                                   │
    └────────────────────────────────────────────────────────────────────┘
```

### 2.4 Jitter

Thêm random delay để tránh thundering herd:

```java
// Với jitter
long delay = baseDelay * Math.pow(multiplier, attempt);
long jitter = (long) (delay * 0.2 * Math.random());  // ±20%
long finalDelay = delay + jitter;
```

---

## Chương 3: Spring Kafka @RetryableTopic

### 3.1 Cấu hình cơ bản

```java
@RetryableTopic(
    attempts = "4",                              // 1 main + 3 retries
    backoff = @Backoff(
        delay = 1000,                            // 1s initial
        multiplier = 2,                          // Double each time
        maxDelay = 10000                         // Max 10s
    ),
    autoCreateTopics = "true",                   // Auto create retry topics
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
)
@KafkaListener(topics = "risk.ingest")
public void consume(PaymentEvent event) {
    processPayment(event);  // Throws exception on failure
}

@DltHandler
public void handleDlt(PaymentEvent event, @Header(KafkaHeaders.EXCEPTION_MESSAGE) String error) {
    log.error("Message sent to DLQ: paymentId={}, error={}", event.getPaymentId(), error);
    alertService.sendAlert("Payment failed permanently: " + event.getPaymentId());
}
```

### 3.2 Topics được tạo tự động

```
risk.ingest              ← Main topic
risk.ingest-retry-0      ← First retry (delay 1s)
risk.ingest-retry-1      ← Second retry (delay 2s)
risk.ingest-retry-2      ← Third retry (delay 4s)
risk.ingest-dlt          ← Dead Letter Topic
```

### 3.3 Message Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    @RetryableTopic MESSAGE FLOW                        │
└─────────────────────────────────────────────────────────────────────────┘

    Producer
       │
       ▼
    ┌─────────────────┐
    │  risk.ingest    │ ◀── Main topic
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │    Consumer     │
    │   @KafkaListener│
    └────────┬────────┘
             │
        Success?
             │
       ┌─────┴─────┐
       │           │
      YES         NO
       │           │
       ▼           ▼
    ┌─────┐   ┌─────────────────┐
    │ ACK │   │ Retry Manager   │
    └─────┘   └────────┬────────┘
                       │
                  Retry < Max?
                       │
                 ┌─────┴─────┐
                 │           │
                YES         NO
                 │           │
                 ▼           ▼
    ┌─────────────────┐  ┌─────────────────┐
    │ risk.ingest-    │  │ risk.ingest-dlt │
    │ retry-{n}       │  │ (DLQ)           │
    └────────┬────────┘  └────────┬────────┘
             │                    │
             │ (after delay)      ▼
             │              ┌─────────────────┐
             └──────────────│   @DltHandler   │
                            └─────────────────┘
```

### 3.4 Exception Classification

```java
@RetryableTopic(
    attempts = "3",
    // Không retry các exception này (gửi thẳng DLQ)
    exclude = {
        IllegalArgumentException.class,
        JsonParseException.class,
        ValidationException.class
    },
    // Chỉ retry các exception này
    include = {
        DatabaseException.class,
        TimeoutException.class,
        ServiceUnavailableException.class
    }
)
```

---

## Chương 4: DLQ Processing

### 4.1 DLQ Consumer

```java
@Component
@Slf4j
public class DlqProcessor {
    
    @KafkaListener(topics = "risk.ingest-dlt", groupId = "dlq-processor")
    public void processDlq(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage,
            @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic,
            @Header(KafkaHeaders.ORIGINAL_OFFSET) long originalOffset) {
        
        log.error("DLQ message received: paymentId={}, originalTopic={}, error={}",
                event.getPaymentId(), originalTopic, errorMessage);
        
        // 1. Store in database for manual review
        dlqRepository.save(DlqMessage.builder()
                .paymentId(event.getPaymentId())
                .originalTopic(originalTopic)
                .errorMessage(errorMessage)
                .payload(serialize(event))
                .status(DlqStatus.PENDING)
                .build());
        
        // 2. Send alert
        alertService.sendSlackAlert(
                "Payment failed permanently",
                Map.of(
                    "payment_id", event.getPaymentId(),
                    "error", errorMessage
                )
        );
        
        // 3. Update metrics
        dlqCounter.increment();
    }
}
```

### 4.2 Manual Replay

```java
@Service
public class DlqReplayService {
    
    public void replayMessage(String dlqMessageId) {
        DlqMessage dlqMessage = dlqRepository.findById(dlqMessageId)
                .orElseThrow(() -> new NotFoundException("DLQ message not found"));
        
        // 1. Deserialize payload
        PaymentEvent event = deserialize(dlqMessage.getPayload());
        
        // 2. Fix data if needed
        event = fixData(event);
        
        // 3. Send back to original topic
        kafkaTemplate.send(dlqMessage.getOriginalTopic(), event.getPaymentId(), event);
        
        // 4. Mark as replayed
        dlqMessage.setStatus(DlqStatus.REPLAYED);
        dlqRepository.save(dlqMessage);
        
        log.info("DLQ message replayed: {}", dlqMessageId);
    }
    
    public void discardMessage(String dlqMessageId, String reason) {
        DlqMessage dlqMessage = dlqRepository.findById(dlqMessageId)
                .orElseThrow();
        
        dlqMessage.setStatus(DlqStatus.DISCARDED);
        dlqMessage.setDiscardReason(reason);
        dlqRepository.save(dlqMessage);
        
        log.info("DLQ message discarded: {}, reason: {}", dlqMessageId, reason);
    }
}
```

### 4.3 DLQ Dashboard

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         DLQ DASHBOARD                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Summary:                                                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │  Pending    │  │  Replayed   │  │  Discarded  │  │   Total     │   │
│  │     23      │  │     156     │  │      8      │  │    187      │   │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘   │
│                                                                         │
│  Recent Messages:                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ ID       │ Payment    │ Error              │ Time    │ Actions │   │
│  ├──────────┼────────────┼────────────────────┼─────────┼─────────┤   │
│  │ dlq-001  │ p-12345    │ DB timeout         │ 5m ago  │ [Replay]│   │
│  │ dlq-002  │ p-12346    │ Invalid amount     │ 10m ago │[Discard]│   │
│  │ dlq-003  │ p-12347    │ Service unavailable│ 15m ago │ [Replay]│   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Chương 5: Best Practices

### 5.1 Retry Configuration

```yaml
# Recommended settings
kafka:
  retry:
    max-attempts: 3          # Không quá nhiều
    initial-delay: 1000      # 1 second
    multiplier: 2            # Exponential
    max-delay: 30000         # Cap at 30s
```

### 5.2 Monitoring

```java
// Metrics to track
Counter retryCounter = Counter.builder("kafka.retry.count")
    .tag("topic", topic)
    .tag("attempt", String.valueOf(attempt))
    .register(registry);

Counter dlqCounter = Counter.builder("kafka.dlq.count")
    .tag("topic", topic)
    .tag("error_type", errorType)
    .register(registry);

// Alerts
- DLQ count > 0 → Warning
- DLQ count > 10/hour → Critical
- Retry rate > 5% → Warning
```

### 5.3 Error Classification

```java
// Classify errors for proper handling
public enum ErrorType {
    TRANSIENT,      // Retry
    PERMANENT,      // DLQ immediately
    UNKNOWN         // Retry then DLQ
}

public ErrorType classifyError(Exception e) {
    if (e instanceof TimeoutException) return TRANSIENT;
    if (e instanceof ValidationException) return PERMANENT;
    if (e instanceof JsonParseException) return PERMANENT;
    return UNKNOWN;
}
```

---

## Tham khảo

- [Spring Kafka Non-Blocking Retries](https://docs.spring.io/spring-kafka/reference/retrytopic.html)
- [Kafka Error Handling](https://www.confluent.io/blog/error-handling-patterns-in-kafka/)
- [Dead Letter Queue Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/DeadLetterChannel.html)
