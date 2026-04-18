# Fraud Detection Gateway — Implementation Plan

> **Mục tiêu:** Xây dựng cổng thanh toán với fraud-detection pipeline, triển khai ≥3 biến thể multi-process, benchmark và viết báo cáo kỹ thuật.

---

## PHASE 0 — Setup & Foundation

| Step | Task | Status |
|------|------|--------|
| 0.1 | **Scaffold:** Gradle multi-module (common/, api-service/, docker-compose) | ✅ |
| 0.2 | **DB migrations (Flyway):** payments, risk_events, feature_cache tables | ✅ |
| 0.3 | **Domain entities:** Payment, RiskEvent, PaymentState enum + state machine | ✅ |
| 0.4 | **Repository layer:** Spring Data JPA + idempotency query | ✅ |
| 0.5 | **Docker-compose:** PostgreSQL, Redis, Kafka, RabbitMQ, Prometheus, Grafana | ✅ |

**Deliverables:**
- `source/build.gradle.kts` (root)
- `source/common/` module với domain entities
- `source/api-service/` module skeleton
- `source/docker-compose.yml`
- DB schema qua Flyway migrations

---

## PHASE 1 — Variant A: Monolith (Baseline)

| Step | Task | Status |
|------|------|--------|
| 1.1 | **REST endpoints skeleton:** 4 APIs trả 200 OK | ✅ |
| 1.2 | **Pipeline in-thread:** Ingest → Feature → Model → Rule → Decision | ✅ |
| 1.3 | **@Transactional + idempotency:** UNIQUE(merchant_id, idempotency_key) | ✅ |
| 1.4 | **Webhook mock:** Log webhook payload (không gọi thật) | ✅ |
| 1.5 | **Micrometer metrics:** Counter, Timer, Gauge cho pipeline | ✅ |
| 1.6 | **Benchmark uniform:** 2000 RPS × 60s → docs/benchmark/A-uniform.txt | ✅ |

**Deliverables:**
- `source/variants/a_monolith/` — working monolith
- `docs/variants/A-monolith.md` — design notes
- `docs/benchmark/A-uniform.txt` — benchmark results

---

## PHASE 2 — Variant B: Thread Pool (ExecutorService)

| Step | Task | Status |
|------|------|--------|
| 2.1 | **ThreadPoolExecutor config:** coreSize, maxSize, queue capacity | ✅ |
| 2.2 | **Async model scoring:** CompletableFuture trong pool | ✅ |
| 2.3 | **Backpressure:** RejectedExecutionHandler → HTTP 429 | ✅ |
| 2.4 | **Graceful shutdown:** awaitTermination + in-flight tracking | ✅ |
| 2.5 | **Benchmark + compare:** So sánh với Variant A | ✅ |

**Deliverables:**
- `source/variants/b_thread_pool/` — thread pool variant
- `docs/variants/B-thread-pool.md` — design notes
- `docs/benchmark/B-uniform.txt` — benchmark results
- `docs/java-concepts/executor-service.md` — concept notes

---

## PHASE 3 — Variant C: Kafka Pipeline

| Step | Task | Status |
|------|------|--------|
| 3.1 | **Kafka topics:** risk.ingest, risk.feature, risk.model, risk.rule, risk.dlq | ⬜ |
| 3.2 | **@KafkaListener:** AcknowledgeMode.MANUAL — ack sau khi xử lý xong | ⬜ |
| 3.3 | **Ingest consumer:** validate, produce to risk.feature | ⬜ |
| 3.4 | **Feature consumer:** enrich, produce downstream | ⬜ |
| 3.5 | **Model consumer:** scoring, produce downstream | ⬜ |
| 3.6 | **Rule consumer:** quyết định, ghi DB, webhook | ⬜ |
| 3.7 | **DLQ:** @RetryableTopic — max 3 lần, sau đó vào risk.dlq | ⬜ |
| 3.8 | **Idempotency per step:** (payment_id, step) unique constraint | ⬜ |
| 3.9 | **Benchmark + compare:** So sánh với A, B | ⬜ |

**Deliverables:**
- `source/variants/c_kafka_pipeline/` — Kafka pipeline variant
- `docs/variants/C-kafka-pipeline.md` — design notes
- `docs/benchmark/C-uniform.txt` — benchmark results
- `docs/java-concepts/kafka-consumer-groups.md` — concept notes

---

## PHASE 4 — Variant D: Per-Merchant Routing (RabbitMQ)

| Step | Task | Status |
|------|------|--------|
| 4.1 | **Exchange + queues:** risk.direct + queues theo merchant_id | ⬜ |
| 4.2 | **Publisher:** route message theo merchant_id (routing key) | ⬜ |
| 4.3 | **Consumer:** 1 process per merchant shard (prefetchCount=1) | ⬜ |
| 4.4 | **Per-tenant rate-limit:** RateLimiter per merchantId | ⬜ |
| 4.5 | **Benchmark + compare:** So sánh với A, B, C | ⬜ |

**Deliverables:**
- `source/variants/d_per_merchant/` — RabbitMQ per-merchant variant
- `docs/variants/D-per-merchant.md` — design notes
- `docs/benchmark/D-uniform.txt` — benchmark results
- `docs/java-concepts/rabbitmq-routing.md` — concept notes

---

## PHASE 5 — Observability & Report

| Step | Task | Status |
|------|------|--------|
| 5.1 | **OpenTelemetry:** Java Agent auto-instrument + custom span per stage | ⬜ |
| 5.2 | **Grafana dashboard:** queue depth, tail latency, decision breakdown | ⬜ |
| 5.3 | **Benchmark scenarios:** hot_merchant, model_slowdown, fault | ⬜ |
| 5.4 | **REPORT.md:** Bảng p50/p95/p99, phân tích trade-off, khuyến nghị | ⬜ |

**Deliverables:**
- `source/docker-compose.yml` — updated with Jaeger, Grafana
- `docs/benchmark/` — all scenario results
- `docs/REPORT.md` — final technical report (5-10 pages)

---

## Benchmark Scenarios

| Scenario | Description | Metrics to capture |
|----------|-------------|-------------------|
| **uniform** | 2000 RPS × 60s, random merchant_id | p50/p95/p99, throughput, error% |
| **hot_merchant** | 70% traffic → merchant_id=m-hot | fairness, queue depth per merchant |
| **model_slowdown** | inject +80ms latency cho 20% requests | tail latency impact, backpressure |
| **fault** | kill 30% model workers 1 phút | retry count, DLQ count, recovery time |

---

## Checklist tự đánh giá

- [ ] ≥ 3 phiên bản hoạt động (A, B, C hoặc D)
- [ ] Crash 1 worker → hệ thống vẫn xử lý, có retry/DLQ
- [ ] Có backpressure (pool đầy hoặc queue sâu)
- [ ] Metrics/logs/tracing đầy đủ
- [ ] Đồ thị queue depth & tail latency
- [ ] Báo cáo p95/p99 end-to-end và per-step
- [ ] So sánh UDS vs MQ/gRPC
- [ ] Chạy được bằng `make up` + `make bench:uniform`

---

## Quick Commands

```bash
# Start all services
make up

# Seed demo data
make seed

# Run benchmarks
make bench:uniform
make bench:hot
make bench:slowdown
make bench:fault

# View logs
make logs

# Cleanup
make down
```

---

## Progress Tracking

Gõ `@status` để xem tiến độ hiện tại.

Gõ `@step 0.1` để bắt đầu bước đầu tiên.
