# Kafka Consumer Patterns — Lý thuyết chuyên sâu

## Chương 1: Consumer Lifecycle

### 1.1 Vòng đời của Consumer

Một Kafka consumer trải qua các giai đoạn sau:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      CONSUMER LIFECYCLE                                 │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─────────────┐
    │   Created   │  ──▶ Consumer instance được tạo
    └──────┬──────┘
           │
           │ subscribe(topics)
           ▼
    ┌─────────────┐
    │ Subscribing │  ──▶ Đăng ký với consumer group
    └──────┬──────┘
           │
           │ poll() lần đầu
           ▼
    ┌─────────────┐
    │  Joining    │  ──▶ Tham gia group, chờ rebalance
    │   Group     │
    └──────┬──────┘
           │
           │ Partitions assigned
           ▼
    ┌─────────────┐
    │  Consuming  │  ──▶ poll() → process → commit
    │   (loop)    │      ↺
    └──────┬──────┘
           │
           │ close() hoặc crash
           ▼
    ┌─────────────┐
    │   Leaving   │  ──▶ Trigger rebalance cho group
    │   Group     │
    └──────┬──────┘
           │
           ▼
    ┌─────────────┐
    │   Closed    │
    └─────────────┘
```

### 1.2 Poll Loop Pattern

**Poll loop** là pattern cơ bản nhất của Kafka consumer:

```java
// Cấu trúc cơ bản của poll loop
while (running) {
    // 1. Poll: lấy batch records từ Kafka
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
    // 2. Process: xử lý từng record
    for (ConsumerRecord<String, String> record : records) {
        processRecord(record);
    }
    
    // 3. Commit: đánh dấu đã xử lý xong
    consumer.commitSync();
}
```

**Tại sao cần poll liên tục?**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    TẠI SAO POLL LIÊN TỤC?                              │
└─────────────────────────────────────────────────────────────────────────┘

    1. HEARTBEAT
       - Consumer gửi heartbeat qua poll()
       - Nếu không poll trong max.poll.interval.ms → bị coi là dead
       - Group coordinator trigger rebalance
    
    2. FETCH DATA
       - poll() fetch data từ broker
       - Batch size controlled by max.poll.records
    
    3. OFFSET MANAGEMENT
       - Commit offset sau khi xử lý
       - Auto-commit hoặc manual commit
    
    Timeline:
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  poll()  process  poll()  process  poll()  process  poll()       │
    │    │       │        │       │        │       │        │           │
    │    ▼       ▼        ▼       ▼        ▼       ▼        ▼           │
    │  ──●───────●────────●───────●────────●───────●────────●──▶ time   │
    │    │                │                │                │           │
    │    └── heartbeat ───┴── heartbeat ───┴── heartbeat ───┘           │
    │                                                                    │
    │  max.poll.interval.ms = 300000 (5 phút)                           │
    │  Nếu không poll trong 5 phút → consumer bị kick khỏi group        │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

---

## Chương 2: Offset Commit Strategies

### 2.1 Vấn đề với Auto-Commit

**Auto-commit** là default behavior nhưng có rủi ro:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    VẤN ĐỀ VỚI AUTO-COMMIT                              │
└─────────────────────────────────────────────────────────────────────────┘

    Scenario: auto.commit.interval.ms = 5000 (5 giây)
    
    Timeline:
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  t=0s     t=2s      t=4s      t=5s      t=6s                      │
    │    │        │         │         │         │                        │
    │  poll()  process   process  AUTO-COMMIT  CRASH!                   │
    │  offset  record1   record2   offset=2    │                        │
    │  0-2       │         │         │         │                        │
    │            ▼         ▼         ▼         ▼                        │
    │         ┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐                     │
    │         │ R0  │   │ R1  │   │ R2  │   │ 💥  │                     │
    │         │ ✓   │   │ ✓   │   │ ✗   │   │     │                     │
    │         └─────┘   └─────┘   └─────┘   └─────┘                     │
    │                              │                                     │
    │                              └── Record 2 chưa xử lý xong         │
    │                                  nhưng đã commit!                  │
    │                                                                    │
    │  Sau restart:                                                      │
    │  - Consumer đọc từ offset 3                                        │
    │  - Record 2 bị MẤT! (đã commit nhưng chưa process xong)           │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

### 2.2 Manual Commit — Giải pháp

**Manual commit** cho phép control chính xác khi nào commit:

```java
// Disable auto-commit
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

while (running) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
    for (ConsumerRecord<String, String> record : records) {
        // 1. Process TRƯỚC
        processRecord(record);
    }
    
    // 2. Commit SAU khi đã xử lý xong TẤT CẢ records trong batch
    consumer.commitSync();
}
```

### 2.3 Commit Strategies So sánh

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    COMMIT STRATEGIES                                    │
└─────────────────────────────────────────────────────────────────────────┘

    1. COMMIT AFTER BATCH (Recommended)
    ┌────────────────────────────────────────────────────────────────────┐
    │  poll() → process R0 → process R1 → process R2 → commitSync()     │
    │                                                        │           │
    │                                                        └── Commit  │
    │                                                            offset 3│
    │  Pros: Simple, efficient (1 commit per batch)                      │
    │  Cons: Nếu crash giữa batch → reprocess cả batch                  │
    └────────────────────────────────────────────────────────────────────┘
    
    2. COMMIT AFTER EACH RECORD
    ┌────────────────────────────────────────────────────────────────────┐
    │  poll() → process R0 → commit → process R1 → commit → ...         │
    │                          │                     │                   │
    │                          └── offset 1          └── offset 2        │
    │                                                                    │
    │  Pros: Minimal reprocessing on crash                               │
    │  Cons: High overhead (1 commit per record)                         │
    └────────────────────────────────────────────────────────────────────┘
    
    3. ASYNC COMMIT WITH SYNC ON CLOSE
    ┌────────────────────────────────────────────────────────────────────┐
    │  poll() → process → commitAsync() → poll() → ... → commitSync()   │
    │                          │                              │          │
    │                          └── Non-blocking               └── Final  │
    │                                                             commit │
    │  Pros: Low latency, reliable on shutdown                           │
    │  Cons: May lose commits on crash                                   │
    └────────────────────────────────────────────────────────────────────┘
```

### 2.4 Spring Kafka AckMode

Spring Kafka cung cấp nhiều **AckMode**:

```java
public enum AckMode {
    RECORD,         // Commit sau mỗi record
    BATCH,          // Commit sau mỗi batch (default)
    TIME,           // Commit theo interval
    COUNT,          // Commit sau N records
    COUNT_TIME,     // Commit theo count HOẶC time
    MANUAL,         // Developer gọi ack.acknowledge()
    MANUAL_IMMEDIATE // Commit ngay khi ack.acknowledge()
}
```

**MANUAL mode** cho phép control tối đa:

```java
@KafkaListener(topics = "risk.ingest")
public void consume(
        ConsumerRecord<String, PaymentEvent> record,
        Acknowledgment ack) {
    
    try {
        // 1. Process
        processPayment(record.value());
        
        // 2. Acknowledge (commit)
        ack.acknowledge();
        
    } catch (Exception e) {
        // 3. Không ack → message sẽ được redelivered
        log.error("Processing failed, will retry", e);
        throw e;
    }
}
```

---

## Chương 3: Error Handling và Retry

### 3.1 Retry Strategies

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    RETRY STRATEGIES                                     │
└─────────────────────────────────────────────────────────────────────────┘

    1. BLOCKING RETRY (Simple)
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  process() ──▶ FAIL ──▶ retry 1 ──▶ FAIL ──▶ retry 2 ──▶ SUCCESS  │
    │      │                    │                    │            │      │
    │      └── 0ms ─────────────┴── 1000ms ──────────┴── 2000ms ──┘      │
    │                                                                    │
    │  Vấn đề: Block consumer, tăng latency                             │
    └────────────────────────────────────────────────────────────────────┘
    
    2. NON-BLOCKING RETRY (Recommended)
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Main Topic          Retry Topic 1        Retry Topic 2     DLQ   │
    │  ┌─────────┐         ┌─────────┐         ┌─────────┐    ┌──────┐  │
    │  │ risk.   │  FAIL   │ risk.   │  FAIL   │ risk.   │FAIL│ risk.│  │
    │  │ ingest  │ ──────▶ │ ingest. │ ──────▶ │ ingest. │───▶│ dlq  │  │
    │  │         │         │ retry-1 │         │ retry-2 │    │      │  │
    │  └─────────┘         └─────────┘         └─────────┘    └──────┘  │
    │       │                   │                   │                   │
    │       │              delay 1s            delay 5s                 │
    │       │                                                           │
    │       └── Không block, tiếp tục process messages khác            │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

### 3.2 Spring Kafka @RetryableTopic

```java
@RetryableTopic(
    attempts = "3",                              // Max 3 lần
    backoff = @Backoff(delay = 1000, multiplier = 2),  // 1s, 2s, 4s
    dltStrategy = DltStrategy.FAIL_ON_ERROR,     // Gửi vào DLQ nếu vẫn fail
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
)
@KafkaListener(topics = "risk.ingest")
public void consume(PaymentEvent event, Acknowledgment ack) {
    processPayment(event);
    ack.acknowledge();
}

@DltHandler
public void handleDlt(PaymentEvent event) {
    // Message đã fail sau tất cả retries
    // Log, alert, manual intervention
    log.error("Message sent to DLQ: {}", event.getPaymentId());
    alertService.sendAlert("Payment failed: " + event.getPaymentId());
}
```

### 3.3 Dead Letter Queue (DLQ)

**DLQ** là nơi chứa messages không thể xử lý:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DEAD LETTER QUEUE                                    │
└─────────────────────────────────────────────────────────────────────────┘

    Tại sao cần DLQ?
    
    1. POISON MESSAGE
       - Message có format sai, không parse được
       - Nếu không có DLQ → consumer stuck forever
    
    2. TRANSIENT FAILURE
       - DB down, network timeout
       - Retry vài lần, nếu vẫn fail → DLQ
    
    3. BUSINESS LOGIC ERROR
       - Invalid data (amount < 0)
       - Không nên retry → DLQ ngay
    
    DLQ Workflow:
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Main Topic                                                        │
    │  ┌─────────┐                                                       │
    │  │ Message │ ──▶ Consumer ──▶ FAIL (3 times) ──┐                  │
    │  └─────────┘                                   │                   │
    │                                                │                   │
    │                                                ▼                   │
    │                                          ┌─────────┐               │
    │                                          │   DLQ   │               │
    │                                          └────┬────┘               │
    │                                               │                    │
    │                    ┌──────────────────────────┼──────────────────┐ │
    │                    │                          │                  │ │
    │                    ▼                          ▼                  ▼ │
    │              ┌──────────┐              ┌──────────┐       ┌──────┐ │
    │              │ Alert    │              │ Manual   │       │ Auto │ │
    │              │ On-call  │              │ Review   │       │Replay│ │
    │              └──────────┘              └──────────┘       └──────┘ │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

---

## Chương 4: Idempotency

### 4.1 Tại sao cần Idempotency?

Kafka đảm bảo **at-least-once delivery**, nghĩa là message có thể được deliver nhiều lần:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DUPLICATE SCENARIOS                                  │
└─────────────────────────────────────────────────────────────────────────┘

    Scenario 1: Consumer crash sau process, trước commit
    ┌────────────────────────────────────────────────────────────────────┐
    │  poll() → process ✓ → CRASH! → restart → poll() → process AGAIN   │
    │                                                                    │
    │  Message được xử lý 2 lần!                                         │
    └────────────────────────────────────────────────────────────────────┘
    
    Scenario 2: Network partition
    ┌────────────────────────────────────────────────────────────────────┐
    │  poll() → process ✓ → commit → TIMEOUT → Kafka không nhận commit  │
    │                                                                    │
    │  Kafka gửi lại message → xử lý 2 lần!                             │
    └────────────────────────────────────────────────────────────────────┘
    
    Scenario 3: Rebalance
    ┌────────────────────────────────────────────────────────────────────┐
    │  Consumer A: poll() → process → REBALANCE → partition chuyển sang B│
    │  Consumer B: poll() → process AGAIN                                │
    │                                                                    │
    │  Message được xử lý bởi cả A và B!                                 │
    └────────────────────────────────────────────────────────────────────┘
```

### 4.2 Idempotent Consumer Pattern

```java
@Service
public class IdempotentConsumer {
    
    @Autowired
    private ProcessedMessageRepository processedRepo;
    
    @Transactional
    public void processIdempotently(PaymentEvent event) {
        String messageId = event.getPaymentId() + "-" + event.getCorrelationId();
        
        // 1. Check if already processed
        if (processedRepo.existsById(messageId)) {
            log.info("Message already processed, skipping: {}", messageId);
            return;  // Idempotent: skip duplicate
        }
        
        // 2. Process
        doProcess(event);
        
        // 3. Mark as processed (trong cùng transaction)
        processedRepo.save(new ProcessedMessage(messageId, Instant.now()));
    }
}
```

### 4.3 Database-based Idempotency

```sql
-- Table để track processed messages
CREATE TABLE processed_messages (
    message_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Unique constraint đảm bảo không duplicate
-- INSERT sẽ fail nếu message_id đã tồn tại
```

---

## Chương 5: Trong Fraud Detection Gateway

### 5.1 Consumer Configuration

```java
@Configuration
public class KafkaConsumerConfig {
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> 
            kafkaListenerContainerFactory() {
        
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory());
        
        // Manual acknowledgment
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        
        // Concurrency = số partitions
        factory.setConcurrency(6);
        
        // Error handler với retry
        factory.setCommonErrorHandler(errorHandler());
        
        return factory;
    }
}
```

### 5.2 Consumer Implementation

```java
@Component
@Slf4j
public class IngestConsumer {
    
    @KafkaListener(
        topics = KafkaTopicConfig.TOPIC_INGEST,
        groupId = "fraud-ingest-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {
        
        MDC.put("payment_id", event.getPaymentId());
        MDC.put("partition", String.valueOf(partition));
        MDC.put("offset", String.valueOf(offset));
        
        try {
            log.info("Processing ingest event");
            
            // 1. Idempotency check
            if (isAlreadyProcessed(event)) {
                log.info("Already processed, skipping");
                ack.acknowledge();
                return;
            }
            
            // 2. Process
            PaymentEvent enriched = processIngest(event);
            
            // 3. Produce to next topic
            kafkaTemplate.send(TOPIC_FEATURE, key, enriched);
            
            // 4. Mark as processed
            markAsProcessed(event);
            
            // 5. Acknowledge
            ack.acknowledge();
            
            log.info("Ingest completed, sent to feature topic");
            
        } catch (Exception e) {
            log.error("Ingest failed: {}", e.getMessage(), e);
            throw e;  // Trigger retry
        } finally {
            MDC.clear();
        }
    }
}
```

---

## Tham khảo

- [Kafka: The Definitive Guide](https://www.confluent.io/resources/kafka-the-definitive-guide/) — Chapter 4: Consumers
- [Spring Kafka Documentation](https://docs.spring.io/spring-kafka/reference/)
- [Exactly-Once Semantics](https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/)
- [Idempotent Consumer Pattern](https://microservices.io/patterns/communication-style/idempotent-consumer.html)
