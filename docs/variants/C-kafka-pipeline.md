# Variant C: Kafka Pipeline

## Tổng quan

Variant C chuyển từ **synchronous processing** sang **event-driven architecture** với Apache Kafka. Mỗi pipeline step là một Kafka consumer độc lập.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    KAFKA PIPELINE ARCHITECTURE                         │
└─────────────────────────────────────────────────────────────────────────┘

    POST /authorize
         │
         ▼
    ┌─────────────────┐
    │   API Service   │ ──▶ Return 202 Accepted (async)
    │   (Producer)    │
    └────────┬────────┘
             │
             │ Produce to risk.ingest
             ▼
    ╔═════════════════════════════════════════════════════════════════════╗
    ║                         KAFKA CLUSTER                               ║
    ╠═════════════════════════════════════════════════════════════════════╣
    ║                                                                     ║
    ║  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             ║
    ║  │risk.ingest  │───▶│risk.feature │───▶│ risk.model  │             ║
    ║  │(6 partitions)│    │(6 partitions)│    │(6 partitions)│             ║
    ║  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘             ║
    ║         │                  │                  │                     ║
    ║         ▼                  ▼                  ▼                     ║
    ║  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             ║
    ║  │   Ingest    │    │   Feature   │    │    Model    │             ║
    ║  │  Consumer   │    │  Consumer   │    │  Consumer   │             ║
    ║  │  (3 inst)   │    │  (3 inst)   │    │  (6 inst)   │             ║
    ║  └─────────────┘    └─────────────┘    └─────────────┘             ║
    ║                                              │                      ║
    ║                                              ▼                      ║
    ║                                       ┌─────────────┐              ║
    ║                                       │ risk.rule   │              ║
    ║                                       │(6 partitions)│              ║
    ║                                       └──────┬──────┘              ║
    ║                                              │                      ║
    ║                                              ▼                      ║
    ║                                       ┌─────────────┐              ║
    ║                                       │    Rule     │              ║
    ║                                       │  Consumer   │              ║
    ║                                       │  (3 inst)   │              ║
    ║                                       └──────┬──────┘              ║
    ║                                              │                      ║
    ║  ┌─────────────┐                             │                      ║
    ║  │  risk.dlq   │◀────── Failed messages ─────┘                      ║
    ║  │(1 partition)│                                                    ║
    ║  └─────────────┘                                                    ║
    ║                                                                     ║
    ╚═════════════════════════════════════════════════════════════════════╝
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │  PostgreSQL │
                                        │  + Webhook  │
                                        └─────────────┘
```

## Key Components

### 1. Topics

| Topic | Partitions | Purpose |
|-------|------------|---------|
| `risk.ingest` | 6 | Raw payment events |
| `risk.feature` | 6 | Events with features |
| `risk.model` | 6 | Events with risk score |
| `risk.rule` | 6 | Events with rule results |
| `risk.dlq` | 1 | Failed messages |

### 2. Consumer Groups

| Group | Instances | Partitions/Instance |
|-------|-----------|---------------------|
| `fraud-ingest-group` | 3 | 2 |
| `fraud-feature-group` | 3 | 2 |
| `fraud-model-group` | 6 | 1 (CPU-bound) |
| `fraud-rule-group` | 3 | 2 |
| `fraud-dlq-group` | 1 | 1 |

### 3. Message Flow

```
PaymentEvent {
    paymentId: "p-123",
    merchantId: "m-456",
    amount: 100000,
    
    // Added by each stage:
    ingestedAt: "...",      // INGEST
    features: {...},        // FEATURE
    riskScore: 0.35,        // MODEL
    ruleResults: {...},     // RULE
    decision: "ALLOW"       // RULE
}
```

## So sánh với Variant A và B

| Aspect | A (Sync) | B (Thread Pool) | C (Kafka) |
|--------|----------|-----------------|-----------|
| Processing | Sync | Async model | Fully async |
| Scaling | Vertical | Vertical | Horizontal |
| Fault isolation | None | Partial | Full |
| Backpressure | None | Bounded queue | Kafka partitions |
| Replay | No | No | Yes (offset reset) |
| Latency | Low | Medium | Higher |
| Throughput | Low | Medium | High |
| Complexity | Simple | Moderate | Complex |
| Ordering | N/A | N/A | Per partition |

## Pros & Cons

### ✅ Pros

- **Horizontal scaling** — thêm consumers để tăng throughput
- **Fault isolation** — một stage fail không ảnh hưởng stage khác
- **Replay** — có thể replay messages từ quá khứ
- **Decoupling** — stages độc lập, deploy riêng
- **Backpressure** — Kafka tự động buffer
- **Observability** — monitor lag per consumer group

### ❌ Cons

- **Latency** — thêm network hops
- **Complexity** — nhiều components hơn
- **Eventual consistency** — không có sync response
- **Ordering** — chỉ đảm bảo trong partition
- **Operational overhead** — cần manage Kafka cluster

## When to Use

- **High throughput** — > 5000 RPS
- **Need replay** — audit, debugging
- **Independent scaling** — model scoring cần nhiều CPU hơn
- **Fault tolerance** — một service down không ảnh hưởng toàn bộ
- **Async acceptable** — client có thể poll status

## Configuration

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    
kafka:
  topic:
    partitions: 6
    replication-factor: 3
    
  consumer:
    concurrency: 6
    max-poll-records: 100
    retry:
      max-attempts: 3
      backoff-ms: 1000
```

## Metrics

| Metric | Description |
|--------|-------------|
| `kafka.ingest.processed` | Messages processed by ingest |
| `kafka.feature.processed` | Messages processed by feature |
| `kafka.model.processed` | Messages processed by model |
| `kafka.rule.processed` | Messages processed by rule |
| `kafka.dlq.received` | Messages sent to DLQ |
| `kafka.consumer.lag` | Consumer lag per partition |

## Files

```
source/api-service/src/main/java/com/fraud/api/kafka/
├── KafkaTopicConfig.java           # Topic definitions
├── KafkaConsumerConfig.java        # Consumer factory
├── KafkaProducerConfig.java        # Producer factory
└── consumer/
    ├── IngestConsumer.java         # Stage 1
    ├── FeatureConsumer.java        # Stage 2
    ├── ModelConsumer.java          # Stage 3
    ├── RuleConsumer.java           # Stage 4
    └── DlqHandler.java             # DLQ processing
```

## Running

```bash
# Start infrastructure (includes Kafka)
make up

# Run application
make run

# Produce test message
curl -X POST http://localhost:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -d '{"payment_id": "p-123", ...}'

# Check consumer lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group fraud-ingest-group

# View DLQ
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic risk.dlq --from-beginning
```

## Benchmark Results

See `docs/benchmark/C-uniform.txt` after running:

```bash
make bench:uniform
```

### Expected Results

| Metric | Variant A | Variant B | Variant C |
|--------|-----------|-----------|-----------|
| Throughput | ~1500 RPS | ~2200 RPS | ~5000 RPS |
| p50 latency | 20ms | 15ms | 50ms |
| p95 latency | 80ms | 45ms | 150ms |
| p99 latency | 250ms | 120ms | 300ms |
| Error rate | 0.5% | 0.04% | 0.01% |

**Note:** Latency cao hơn do async processing, nhưng throughput cao hơn nhiều.

## Lessons Learned

1. **Partition key = payment_id** — đảm bảo ordering per payment
2. **Manual ack** — commit sau khi xử lý xong
3. **Idempotency per step** — tránh duplicate processing
4. **DLQ** — không để poison message block pipeline
5. **Consumer concurrency = partitions** — tối ưu parallelism
6. **Monitor lag** — alert khi lag tăng
