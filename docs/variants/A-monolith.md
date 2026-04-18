# Variant A: Monolith (Baseline)

## Tổng quan

Variant A là **baseline** — tất cả pipeline steps chạy **tuần tự trong cùng 1 thread** của HTTP request. Không có IPC, không có message queue.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     SINGLE JVM PROCESS                          │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                   HTTP Request Thread                     │  │
│  │                                                           │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐      │  │
│  │  │ INGEST  │─▶│ FEATURE │─▶│  MODEL  │─▶│  RULE   │──┐   │  │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────┘  │   │  │
│  │                                                       │   │  │
│  │  ┌──────────────────────────────────────────────────┐ │   │  │
│  │  │                   DECISION                       │◀┘   │  │
│  │  └──────────────────────────────────────────────────┘     │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Key Components

### 1. Pipeline Steps

| Step | Class | Responsibility |
|------|-------|----------------|
| INGEST | `IngestStep` | Validate, normalize data |
| FEATURE | `FeatureStep` | Extract features (mock) |
| MODEL | `ModelStep` | Calculate risk score (heuristic) |
| RULE | `RuleStep` | Apply business rules, blacklists |
| DECISION | `DecisionStep` | Combine results → ALLOW/REVIEW/BLOCK |

### 2. Idempotency

```java
// PaymentRepository
Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

// Database constraint
UNIQUE (merchant_id, idempotency_key)
```

**Flow:**
1. Check if payment exists with same `(merchant_id, idempotency_key)`
2. If exists → return cached result
3. If not → process pipeline, save result

### 3. State Machine

```
PENDING ──┬──▶ DECIDED (ALLOW)
          ├──▶ REVIEW  ──┬──▶ DECIDED (human approves)
          │              └──▶ BLOCKED (human rejects)
          └──▶ BLOCKED (BLOCK)
```

### 4. Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `authorize_requests_total` | Counter | Total requests |
| `authorize_requests_inflight` | Gauge | In-flight requests |
| `decision_total{type}` | Counter | Decisions by type |
| `pipeline.step.duration{step}` | Timer | Per-step latency |

## Pros & Cons

### ✅ Pros
- **Simple** — no IPC, no message queue
- **Low latency** — no network hops
- **Easy debugging** — single thread, stack trace
- **Transactional** — all steps in one DB transaction

### ❌ Cons
- **No isolation** — one slow step blocks everything
- **No horizontal scaling** — can only scale vertically
- **No fault tolerance** — crash = lost request
- **No backpressure** — thread pool exhaustion

## When to Use

- **Development/testing** — simple to run and debug
- **Low traffic** — < 1000 RPS
- **Baseline comparison** — measure improvement of other variants

## Files

```
source/api-service/src/main/java/com/fraud/api/
├── controller/
│   ├── PaymentController.java
│   ├── ReviewController.java
│   └── MetricsController.java
├── service/
│   ├── PaymentService.java
│   ├── ReviewService.java
│   ├── MetricsService.java
│   └── WebhookService.java
├── pipeline/
│   ├── FraudDetectionPipeline.java
│   ├── PipelineContext.java
│   ├── PipelineStep.java
│   └── steps/
│       ├── IngestStep.java
│       ├── FeatureStep.java
│       ├── ModelStep.java
│       ├── RuleStep.java
│       └── DecisionStep.java
├── domain/
│   ├── Payment.java
│   └── RiskEvent.java
├── repository/
│   ├── PaymentRepository.java
│   └── RiskEventRepository.java
└── exception/
    ├── PaymentNotFoundException.java
    ├── IdempotencyConflictException.java
    └── InvalidStateTransitionException.java
```

## Running

```bash
# Start infrastructure
make up

# Run application
make run

# Test endpoint
curl -X POST http://localhost:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "payment_id": "p-123",
    "merchant_id": "m-1",
    "amount": 100000,
    "currency": "VND",
    "card_bin": "412345",
    "ip": "1.2.3.4",
    "device_id": "d-abc",
    "idempotency_key": "idem-123"
  }'
```

## Benchmark Results

See `docs/benchmark/A-uniform.txt` after running:

```bash
make bench:uniform
```
