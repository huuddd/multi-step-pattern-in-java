# Variant D: Per-Merchant Routing (RabbitMQ)

## Tổng quan

Variant D sử dụng **RabbitMQ** với **per-merchant queues** để đảm bảo **tenant isolation** và **fair processing**. Mỗi merchant có queue riêng, tránh noisy neighbor problem.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PER-MERCHANT RABBITMQ ARCHITECTURE                  │
└─────────────────────────────────────────────────────────────────────────┘

    POST /authorize
         │
         ▼
    ┌─────────────────┐
    │   API Service   │
    │   (Publisher)   │
    └────────┬────────┘
             │
             │ publish(exchange="risk.direct", routing_key=merchantId)
             ▼
    ╔═════════════════════════════════════════════════════════════════════╗
    ║                       RABBITMQ BROKER                               ║
    ╠═════════════════════════════════════════════════════════════════════╣
    ║                                                                     ║
    ║  ┌─────────────────────────────────────────────────────────────┐   ║
    ║  │                    risk.direct (Exchange)                    │   ║
    ║  └──────────────────────────┬──────────────────────────────────┘   ║
    ║                             │                                       ║
    ║         ┌───────────────────┼───────────────────┐                  ║
    ║         │                   │                   │                  ║
    ║         ▼                   ▼                   ▼                  ║
    ║  ┌────────────┐      ┌────────────┐      ┌────────────┐           ║
    ║  │merchant.001│      │merchant.002│      │merchant.hot│           ║
    ║  │  (Queue)   │      │  (Queue)   │      │  (Queue)   │           ║
    ║  │ prefetch=1 │      │ prefetch=1 │      │ prefetch=1 │           ║
    ║  └─────┬──────┘      └─────┬──────┘      └─────┬──────┘           ║
    ║        │                   │                   │                   ║
    ║        │                   │                   │                   ║
    ║  ┌─────┴──────┐      ┌─────┴──────┐      ┌─────┴──────┐           ║
    ║  │ Consumer 1 │      │ Consumer 2 │      │ Consumer 3 │           ║
    ║  │ RateLimit  │      │ RateLimit  │      │ Consumer 4 │           ║
    ║  │ 100 RPS    │      │ 100 RPS    │      │ Consumer 5 │           ║
    ║  └────────────┘      └────────────┘      │ RateLimit  │           ║
    ║                                          │ 1000 RPS   │           ║
    ║                                          └────────────┘           ║
    ║                                                                     ║
    ║  ┌─────────────┐                                                   ║
    ║  │  risk.dlq   │◀────── Failed messages (DLX)                      ║
    ║  └─────────────┘                                                   ║
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

### 1. Exchange và Queues

| Component | Type | Purpose |
|-----------|------|---------|
| `risk.direct` | Direct Exchange | Route by merchantId |
| `merchant.{id}` | Queue | Per-merchant queue |
| `risk.dlx` | Direct Exchange | Dead letter exchange |
| `risk.dlq` | Queue | Dead letter queue |

### 2. Queue Properties

```yaml
# Per-merchant queue configuration
x-message-ttl: 300000        # 5 minutes TTL
x-max-length: 10000          # Max 10k messages
x-dead-letter-exchange: risk.dlx
x-dead-letter-routing-key: dlq
```

### 3. Consumer Configuration

```yaml
# Consumer settings
prefetch-count: 1            # Fair dispatch
acknowledge-mode: MANUAL     # Explicit ack
concurrent-consumers: 1      # Per queue
```

### 4. Rate Limiting

| Merchant Tier | Rate Limit |
|---------------|------------|
| Basic | 100 RPS |
| Pro | 500 RPS |
| Enterprise | 2000 RPS |

## So sánh với các Variants khác

| Aspect | A (Sync) | B (Thread Pool) | C (Kafka) | D (RabbitMQ) |
|--------|----------|-----------------|-----------|--------------|
| Processing | Sync | Async model | Fully async | Async per-tenant |
| Tenant isolation | None | None | Partition | Full (queue) |
| Fair dispatch | N/A | Bounded queue | Partition | prefetch=1 |
| Rate limiting | Global | Global | Per-partition | Per-tenant |
| Noisy neighbor | Yes | Yes | Partial | No |
| Complexity | Simple | Moderate | Complex | Moderate |
| Replay | No | No | Yes | No |
| Ordering | N/A | N/A | Per-partition | Per-queue |

## Pros & Cons

### ✅ Pros

- **Full tenant isolation** — mỗi merchant có queue riêng
- **No noisy neighbor** — hot merchant không ảnh hưởng others
- **Fair dispatch** — prefetch=1 đảm bảo fair processing
- **Per-tenant rate limiting** — configurable per merchant
- **Dynamic scaling** — thêm consumers cho hot merchants
- **Simple routing** — direct exchange với merchantId

### ❌ Cons

- **Queue proliferation** — nhiều queues nếu nhiều merchants
- **No replay** — messages deleted after consume
- **Management overhead** — monitor nhiều queues
- **Memory usage** — mỗi queue có overhead
- **No ordering across merchants** — chỉ order trong queue

## When to Use

- **Multi-tenant SaaS** — cần tenant isolation
- **Tiered pricing** — different rate limits per tier
- **Hot tenant scenario** — một số merchants traffic cao
- **Fair processing** — đảm bảo SLA cho tất cả merchants
- **Simple routing** — không cần complex topic patterns

## Configuration

```yaml
# application.yml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASS:guest}

rabbitmq:
  consumer:
    prefetch-count: 1
  queue:
    message-ttl: 300000
    max-length: 10000

rate-limit:
  default-permits-per-second: 100
```

## Metrics

| Metric | Description |
|--------|-------------|
| `rabbitmq.messages.published` | Messages published |
| `rabbitmq.messages.processed` | Messages processed |
| `rabbitmq.messages.rejected` | Rate limited messages |
| `rabbitmq.processing.duration` | Processing time |
| `rate_limiter.cache.size` | Active rate limiters |

## Files

```
source/api-service/src/main/java/com/fraud/api/rabbitmq/
├── RabbitMQConfig.java           # Exchange, queue, factory
├── MerchantQueueManager.java     # Dynamic queue creation
├── MerchantMessagePublisher.java # Publish to merchant queue
├── MerchantConsumer.java         # Full pipeline consumer
└── PerMerchantRateLimiter.java   # Guava RateLimiter per merchant
```

## Running

```bash
# Start infrastructure (includes RabbitMQ)
make up

# Run application
make run

# Send test request
curl -X POST http://localhost:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -d '{"payment_id": "p-123", "merchant_id": "m-001", ...}'

# Check queues
rabbitmqctl list_queues name messages consumers

# Check rate limiter stats
curl http://localhost:8080/actuator/metrics/rate_limiter.cache.size
```

## Benchmark Results

See `docs/benchmark/D-uniform.txt` after running:

```bash
make bench:uniform
```

### Expected Results

| Metric | A | B | C | D |
|--------|---|---|---|---|
| Throughput | ~1500 | ~2200 | ~5000 | ~3000 |
| p50 latency | 20ms | 15ms | 50ms | 25ms |
| p95 latency | 80ms | 45ms | 150ms | 60ms |
| p99 latency | 250ms | 120ms | 300ms | 150ms |
| Error rate | 0.5% | 0.04% | 0.01% | 0.02% |
| Tenant isolation | ❌ | ❌ | Partial | ✅ |

**Note:** Throughput thấp hơn Kafka do per-queue overhead, nhưng tenant isolation tốt hơn.

## Hot Merchant Scenario

```
Scenario: merchant-hot gửi 5000 RPS, merchant-normal gửi 100 RPS

Without per-tenant routing (Variant A/B):
┌────────────────────────────────────────────────────────────────────┐
│  merchant-hot: 5000 RPS → Processed 5000 RPS                      │
│  merchant-normal: 100 RPS → Processed 10 RPS (starved!)           │
│                                                                    │
│  merchant-normal p99 latency: 5000ms (timeout)                    │
└────────────────────────────────────────────────────────────────────┘

With per-tenant routing (Variant D):
┌────────────────────────────────────────────────────────────────────┐
│  merchant-hot: 5000 RPS → Rate limited to 1000 RPS                │
│  merchant-normal: 100 RPS → Processed 100 RPS ✓                   │
│                                                                    │
│  merchant-normal p99 latency: 60ms (normal)                       │
└────────────────────────────────────────────────────────────────────┘
```

## Lessons Learned

1. **prefetch=1** — critical for fair dispatch
2. **Dynamic queues** — create on first message
3. **Rate limiting** — per-tenant, not global
4. **DLX** — handle failed messages properly
5. **TTL** — prevent queue buildup
6. **Monitor queue depth** — alert on backlog
