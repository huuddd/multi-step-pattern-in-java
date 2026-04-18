# Apache Kafka — Nền tảng lý thuyết

## Chương 1: Giới thiệu

### 1.1 Kafka là gì?

**Apache Kafka** là một **distributed event streaming platform** được thiết kế để xử lý hàng triệu events mỗi giây với độ trễ thấp và độ tin cậy cao.

Kafka được LinkedIn phát triển năm 2011 và sau đó trở thành dự án Apache. Tên "Kafka" được đặt theo nhà văn Franz Kafka vì người sáng lập muốn một cái tên "đẹp" cho hệ thống viết (write) dữ liệu.

### 1.2 Tại sao cần Kafka?

Hãy tưởng tượng một hệ thống thanh toán:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    VẤN ĐỀ: POINT-TO-POINT INTEGRATION                  │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────┐     ┌──────────┐     ┌──────────┐
    │ Payment  │────▶│ Fraud    │────▶│ Webhook  │
    │ Service  │     │ Service  │     │ Service  │
    └────┬─────┘     └────┬─────┘     └──────────┘
         │                │
         │                ▼
         │           ┌──────────┐
         │           │ Analytics│
         │           │ Service  │
         │           └──────────┘
         │
         ▼
    ┌──────────┐
    │ Audit    │
    │ Service  │
    └──────────┘

    Vấn đề:
    - N services → N×(N-1) connections
    - Tight coupling
    - Nếu Fraud Service down → Payment Service blocked
    - Không replay được events cũ
```

Với Kafka:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    GIẢI PHÁP: EVENT-DRIVEN với KAFKA                   │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────┐                              ┌──────────┐
    │ Payment  │──┐                       ┌──▶│ Fraud    │
    │ Service  │  │                       │   │ Service  │
    └──────────┘  │   ┌───────────────┐   │   └──────────┘
                  ├──▶│               │───┤
    ┌──────────┐  │   │    KAFKA      │   │   ┌──────────┐
    │ Mobile   │──┤   │               │───┼──▶│ Webhook  │
    │ App      │  │   │  ┌─────────┐  │   │   │ Service  │
    └──────────┘  │   │  │ Topics  │  │   │   └──────────┘
                  │   │  └─────────┘  │   │
    ┌──────────┐  │   └───────────────┘   │   ┌──────────┐
    │ Web App  │──┘                       └──▶│ Analytics│
    └──────────┘                              │ Service  │
                                              └──────────┘

    Lợi ích:
    - Loose coupling (producers không biết consumers)
    - Fault tolerance (Kafka buffer messages)
    - Replay (đọc lại events từ quá khứ)
    - Scale independently
```

### 1.3 Các khái niệm cốt lõi

| Khái niệm | Định nghĩa | Ví dụ |
|-----------|------------|-------|
| **Event** | Một sự kiện đã xảy ra | "Payment p-123 created" |
| **Topic** | Danh mục chứa events | "payments", "fraud-checks" |
| **Partition** | Phân vùng của topic | Topic "payments" có 3 partitions |
| **Producer** | Gửi events vào topic | Payment Service |
| **Consumer** | Đọc events từ topic | Fraud Service |
| **Broker** | Server Kafka | kafka-1, kafka-2, kafka-3 |
| **Cluster** | Nhóm brokers | Production cluster |

---

## Chương 2: Topics và Partitions

### 2.1 Topic là gì?

**Topic** là một **append-only log** — events chỉ được thêm vào cuối, không sửa, không xóa.

```
Topic: payments
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│  Offset:  0      1      2      3      4      5      6      7           │
│         ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐        │
│         │ E0 │ │ E1 │ │ E2 │ │ E3 │ │ E4 │ │ E5 │ │ E6 │ │ E7 │ ──▶    │
│         └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘        │
│                                                                         │
│  ◀─────────────────── Immutable Log ───────────────────▶               │
│                                                                         │
│  - Events được append vào cuối                                         │
│  - Mỗi event có offset duy nhất                                        │
│  - Events được giữ theo retention policy (7 ngày, 1TB, ...)            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Partition là gì?

**Partition** là đơn vị parallelism của Kafka. Một topic được chia thành nhiều partitions để:
1. **Scale horizontally** — nhiều consumers đọc song song
2. **Distribute load** — spread across brokers
3. **Guarantee ordering** — trong cùng partition

```
Topic: payments (3 partitions)
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│  Partition 0 (Broker 1):                                               │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐                                           │
│  │ P0 │ │ P3 │ │ P6 │ │ P9 │ ──▶  (merchant_id % 3 == 0)              │
│  └────┘ └────┘ └────┘ └────┘                                           │
│                                                                         │
│  Partition 1 (Broker 2):                                               │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐                                           │
│  │ P1 │ │ P4 │ │ P7 │ │P10│ ──▶  (merchant_id % 3 == 1)               │
│  └────┘ └────┘ └────┘ └────┘                                           │
│                                                                         │
│  Partition 2 (Broker 3):                                               │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐                                           │
│  │ P2 │ │ P5 │ │ P8 │ │P11│ ──▶  (merchant_id % 3 == 2)               │
│  └────┘ └────┘ └────┘ └────┘                                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

Partition Key: merchant_id
- Tất cả payments của merchant "m-1" luôn vào cùng partition
- Đảm bảo ordering per merchant
```

### 2.3 Partition Key và Ordering

**Kafka chỉ đảm bảo ordering trong cùng một partition.**

```java
// Producer gửi với partition key
producer.send(new ProducerRecord<>(
    "payments",           // topic
    merchantId,           // key → quyết định partition
    paymentEvent          // value
));

// Partition = hash(key) % numPartitions
// Nếu key = "m-123", hash("m-123") % 3 = 1 → Partition 1
```

**Tại sao quan trọng?**

```
Scenario: 2 events cho cùng payment

Event 1: Payment CREATED (offset 100)
Event 2: Payment DECIDED (offset 101)

Nếu CÙNG partition:
  Consumer đọc: CREATED → DECIDED ✓ (đúng thứ tự)

Nếu KHÁC partition:
  Consumer có thể đọc: DECIDED → CREATED ✗ (sai thứ tự!)
```

### 2.4 Replication

Mỗi partition được replicate sang nhiều brokers để fault tolerance:

```
Topic: payments, Partition 0, Replication Factor = 3
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│  Broker 1 (Leader):     ┌────┐ ┌────┐ ┌────┐ ┌────┐                    │
│                         │ E0 │ │ E1 │ │ E2 │ │ E3 │  ◀── Writes go here│
│                         └────┘ └────┘ └────┘ └────┘                    │
│                              │                                          │
│                              │ Replicate                                │
│                              ▼                                          │
│  Broker 2 (Follower):   ┌────┐ ┌────┐ ┌────┐ ┌────┐                    │
│                         │ E0 │ │ E1 │ │ E2 │ │ E3 │  ◀── In-sync       │
│                         └────┘ └────┘ └────┘ └────┘                    │
│                              │                                          │
│                              │ Replicate                                │
│                              ▼                                          │
│  Broker 3 (Follower):   ┌────┐ ┌────┐ ┌────┐ ┌────┐                    │
│                         │ E0 │ │ E1 │ │ E2 │ │ E3 │  ◀── In-sync       │
│                         └────┘ └────┘ └────┘ └────┘                    │
│                                                                         │
│  Nếu Broker 1 chết → Broker 2 hoặc 3 trở thành Leader                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Chương 3: Producers

### 3.1 Producer Workflow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         PRODUCER WORKFLOW                               │
└─────────────────────────────────────────────────────────────────────────┘

    Application
         │
         │ send(topic, key, value)
         ▼
    ┌─────────────────┐
    │   Serializer    │  ──▶ Convert Java object to bytes
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │   Partitioner   │  ──▶ Decide which partition (hash(key) % N)
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │  Record Buffer  │  ──▶ Batch records for efficiency
    │  (per partition)│
    └────────┬────────┘
             │
             │ batch.size reached OR linger.ms elapsed
             ▼
    ┌─────────────────┐
    │   Sender Thread │  ──▶ Send batch to broker
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │  Kafka Broker   │  ──▶ Write to partition log
    └────────┬────────┘
             │
             │ acks
             ▼
    ┌─────────────────┐
    │   Callback      │  ──▶ Notify application
    └─────────────────┘
```

### 3.2 Acknowledgments (acks)

| acks | Meaning | Durability | Latency |
|------|---------|------------|---------|
| `0` | Fire and forget | Lowest | Lowest |
| `1` | Leader acknowledged | Medium | Medium |
| `all` | All in-sync replicas | Highest | Highest |

```java
// Production setting: acks=all for durability
props.put(ProducerConfig.ACKS_CONFIG, "all");
```

### 3.3 Idempotent Producer

```java
// Enable exactly-once semantics
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

// Kafka assigns sequence number to each message
// If duplicate detected (same sequence), broker ignores it
```

---

## Chương 4: Consumers và Consumer Groups

### 4.1 Consumer Group là gì?

**Consumer Group** là một nhóm consumers cùng đọc từ một topic. Mỗi partition chỉ được đọc bởi **một consumer** trong group.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CONSUMER GROUP                                  │
└─────────────────────────────────────────────────────────────────────────┘

Topic: payments (6 partitions)
Consumer Group: fraud-service (3 consumers)

    ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
    │  P0     │ │  P1     │ │  P2     │ │  P3     │ │  P4     │ │  P5     │
    └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
         │           │           │           │           │           │
         └─────┬─────┘           └─────┬─────┘           └─────┬─────┘
               │                       │                       │
               ▼                       ▼                       ▼
         ┌──────────┐           ┌──────────┐           ┌──────────┐
         │Consumer 1│           │Consumer 2│           │Consumer 3│
         │ (P0, P1) │           │ (P2, P3) │           │ (P4, P5) │
         └──────────┘           └──────────┘           └──────────┘

    Mỗi consumer xử lý 2 partitions
    → Parallelism = min(partitions, consumers) = 3
```

### 4.2 Rebalancing

Khi consumer join/leave group, partitions được **rebalance**:

```
Trước: 3 consumers, 6 partitions
┌──────────┐  ┌──────────┐  ┌──────────┐
│Consumer 1│  │Consumer 2│  │Consumer 3│
│ P0, P1   │  │ P2, P3   │  │ P4, P5   │
└──────────┘  └──────────┘  └──────────┘

Consumer 3 crashes!

Sau: 2 consumers, 6 partitions (rebalance)
┌──────────┐  ┌──────────┐
│Consumer 1│  │Consumer 2│
│ P0, P1,  │  │ P3, P4,  │
│ P2       │  │ P5       │
└──────────┘  └──────────┘
```

### 4.3 Offset Management

**Offset** là vị trí của consumer trong partition:

```
Partition 0:
┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
│ 0  │ 1  │ 2  │ 3  │ 4  │ 5  │ 6  │ 7  │ 8  │ 9  │
└────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
                    ▲                         ▲
                    │                         │
            Committed Offset = 4      Log End Offset = 9
            (đã xử lý xong)           (message mới nhất)

            Lag = 9 - 4 = 5 messages chưa xử lý
```

### 4.4 Commit Strategies

```java
// Auto commit (default, risky)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);
// Vấn đề: commit trước khi xử lý xong → mất message

// Manual commit (recommended)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, String> record : records) {
        process(record);  // Xử lý trước
    }
    consumer.commitSync();  // Commit sau khi xử lý xong
}
```

---

## Chương 5: Delivery Semantics

### 5.1 Ba mức đảm bảo

| Semantics | Meaning | Use case |
|-----------|---------|----------|
| **At-most-once** | Có thể mất message | Metrics, logs |
| **At-least-once** | Có thể duplicate | Default, với idempotent consumer |
| **Exactly-once** | Không mất, không duplicate | Financial transactions |

### 5.2 At-least-once (Recommended)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      AT-LEAST-ONCE DELIVERY                            │
└─────────────────────────────────────────────────────────────────────────┘

    1. Consumer poll message (offset 5)
    2. Process message
    3. Commit offset 5
    
    Nếu crash sau step 2, trước step 3:
    - Restart: poll lại từ offset 5
    - Message được xử lý LẦN THỨ 2 (duplicate)
    
    Giải pháp: Idempotent consumer
    - Check: "Đã xử lý payment p-123 chưa?"
    - Nếu rồi → skip
    - Nếu chưa → process
```

### 5.3 Exactly-once với Transactions

```java
// Producer với transaction
producer.initTransactions();
try {
    producer.beginTransaction();
    producer.send(record1);
    producer.send(record2);
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}

// Consumer với isolation
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
// Chỉ đọc messages từ committed transactions
```

---

## Chương 6: Trong Fraud Detection Gateway

### 6.1 Topic Design

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    KAFKA PIPELINE TOPOLOGY                              │
└─────────────────────────────────────────────────────────────────────────┘

    POST /authorize
         │
         ▼
    ┌─────────────────┐
    │   API Service   │
    │   (Producer)    │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐     ┌─────────────────┐
    │  risk.ingest    │────▶│ Ingest Consumer │
    │  (6 partitions) │     └────────┬────────┘
    └─────────────────┘              │
                                     ▼
    ┌─────────────────┐     ┌─────────────────┐
    │  risk.feature   │────▶│Feature Consumer │
    │  (6 partitions) │     └────────┬────────┘
    └─────────────────┘              │
                                     ▼
    ┌─────────────────┐     ┌─────────────────┐
    │  risk.model     │────▶│ Model Consumer  │
    │  (6 partitions) │     └────────┬────────┘
    └─────────────────┘              │
                                     ▼
    ┌─────────────────┐     ┌─────────────────┐
    │  risk.rule      │────▶│ Rule Consumer   │
    │  (6 partitions) │     └────────┬────────┘
    └─────────────────┘              │
                                     ▼
                            ┌─────────────────┐
                            │   DB + Webhook  │
                            └─────────────────┘

    ┌─────────────────┐
    │  risk.dlq       │  ◀── Failed messages after 3 retries
    │  (1 partition)  │
    └─────────────────┘
```

### 6.2 Partition Key Strategy

```java
// Partition by payment_id
// → Tất cả events của cùng payment vào cùng partition
// → Đảm bảo ordering per payment

String partitionKey = paymentEvent.getPaymentId();
producer.send(new ProducerRecord<>("risk.ingest", partitionKey, paymentEvent));
```

### 6.3 Consumer Group Design

```
Consumer Group: fraud-ingest-group
  - 3 instances → mỗi instance xử lý 2 partitions

Consumer Group: fraud-feature-group
  - 3 instances → mỗi instance xử lý 2 partitions

Consumer Group: fraud-model-group
  - 6 instances → mỗi instance xử lý 1 partition (CPU-bound)

Consumer Group: fraud-rule-group
  - 3 instances → mỗi instance xử lý 2 partitions
```

---

## Tham khảo

- [Kafka: The Definitive Guide](https://www.confluent.io/resources/kafka-the-definitive-guide/) — O'Reilly
- [Designing Data-Intensive Applications](https://dataintensive.net/) — Martin Kleppmann, Chapter 11
- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [Confluent Developer](https://developer.confluent.io/)
