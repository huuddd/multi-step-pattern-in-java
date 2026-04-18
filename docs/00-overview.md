# Fraud Detection Gateway — Architecture Overview

## 1. Tổng quan hệ thống

Hệ thống **Fraud Detection Gateway** là cổng thanh toán tích hợp với nhiều merchant. Mỗi giao dịch trước khi authorize phải đi qua **fraud-detection pipeline** gồm 5 bước chính.

### 1.1 ASCII Diagram — High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           FRAUD DETECTION GATEWAY                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│   ┌──────────┐    ┌─────────────────────────────────────────────────────────┐  │
│   │ Merchant │───▶│                    API Gateway                          │  │
│   │  Client  │◀───│  POST /payments/authorize  │  GET /payments/{id}/status │  │
│   └──────────┘    │  POST /reviews/{id}/decision │ GET /metrics/stats       │  │
│                   └─────────────────────┬───────────────────────────────────┘  │
│                                         │                                       │
│                                         ▼                                       │
│   ┌─────────────────────────────────────────────────────────────────────────┐  │
│   │                      FRAUD DETECTION PIPELINE                           │  │
│   │                                                                         │  │
│   │  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌──────────┐  │  │
│   │  │ INGEST  │──▶│ FEATURE │──▶│  MODEL  │──▶│  RULE   │──▶│ DECISION │  │  │
│   │  │         │   │EXTRACTION│   │ SCORING │   │  EVAL   │   │          │  │  │
│   │  └─────────┘   └─────────┘   └─────────┘   └─────────┘   └──────────┘  │  │
│   │       │             │             │             │             │         │  │
│   │       ▼             ▼             ▼             ▼             ▼         │  │
│   │  ┌─────────────────────────────────────────────────────────────────┐   │  │
│   │  │                     risk_events (audit log)                     │   │  │
│   │  └─────────────────────────────────────────────────────────────────┘   │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
│                                         │                                       │
│                                         ▼                                       │
│   ┌─────────────────────────────────────────────────────────────────────────┐  │
│   │                           DATA STORES                                   │  │
│   │  ┌────────────┐   ┌────────────┐   ┌────────────┐   ┌────────────┐     │  │
│   │  │ PostgreSQL │   │   Redis    │   │   Kafka    │   │  RabbitMQ  │     │  │
│   │  │  payments  │   │   cache    │   │  (var C)   │   │  (var D)   │     │  │
│   │  │risk_events │   │feature_cache│   │            │   │            │     │  │
│   │  └────────────┘   └────────────┘   └────────────┘   └────────────┘     │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
│                                         │                                       │
│                                         ▼                                       │
│   ┌─────────────────────────────────────────────────────────────────────────┐  │
│   │                         OBSERVABILITY                                   │  │
│   │  ┌────────────┐   ┌────────────┐   ┌────────────┐   ┌────────────┐     │  │
│   │  │ Prometheus │   │  Grafana   │   │   Jaeger   │   │ Structured │     │  │
│   │  │  metrics   │   │ dashboards │   │  tracing   │   │    Logs    │     │  │
│   │  └────────────┘   └────────────┘   └────────────┘   └────────────┘     │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Pipeline Steps Detail

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         PIPELINE FLOW (per payment)                          │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐                                                         │
│  │     INGEST      │  • Validate request schema                              │
│  │   (validate)    │  • Check idempotency_key → dedupe                       │
│  │                 │  • Normalize data (currency, amount)                    │
│  └────────┬────────┘  • Create Payment record (state=PENDING)                │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐                                                         │
│  │    FEATURE      │  • Query DB: merchant history, device fingerprint       │
│  │   EXTRACTION    │  • Query Redis: recent transactions                     │
│  │                 │  • Query 3rd-party: IP geolocation, BIN lookup          │
│  └────────┬────────┘  • Cache features for reuse                             │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐                                                         │
│  │     MODEL       │  • ML model inference (or heuristic scoring)            │
│  │    SCORING      │  • Output: risk_score (0.0 - 1.0)                       │
│  │                 │  • CPU-bound → candidate for worker pool                │
│  └────────┬────────┘                                                         │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐                                                         │
│  │      RULE       │  • Blacklist check (card_bin, device_id, IP)            │
│  │   EVALUATION    │  • Geofence rules (IP vs billing country)               │
│  │                 │  • Velocity rules (amount/time window)                  │
│  └────────┬────────┘  • Threshold rules (risk_score > X)                     │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐                                                         │
│  │    DECISION     │  • Combine model + rules → ALLOW / REVIEW / BLOCK       │
│  │                 │  • Update Payment (state, decision, risk_score)         │
│  │                 │  • Send webhook to merchant (idempotent)                │
│  └─────────────────┘                                                         │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Payment State Machine

```
                    ┌─────────────────────────────────────────────┐
                    │           PAYMENT STATE MACHINE             │
                    └─────────────────────────────────────────────┘

                              ┌─────────────┐
                              │   PENDING   │
                              │  (initial)  │
                              └──────┬──────┘
                                     │
                    ┌────────────────┼────────────────┐
                    │                │                │
                    ▼                ▼                ▼
            ┌───────────┐    ┌───────────┐    ┌───────────┐
            │  DECIDED  │    │  REVIEW   │    │  BLOCKED  │
            │(terminal) │    │ (waiting) │    │(terminal) │
            └───────────┘    └─────┬─────┘    └───────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
                    ▼                             ▼
            ┌───────────┐                 ┌───────────┐
            │  DECIDED  │                 │  BLOCKED  │
            │  (ALLOW)  │                 │  (BLOCK)  │
            └───────────┘                 └───────────┘


    ┌────────────────────────────────────────────────────────────────┐
    │                    STATE TRANSITIONS                           │
    ├────────────────────────────────────────────────────────────────┤
    │  From       │  To        │  Trigger                            │
    ├─────────────┼────────────┼─────────────────────────────────────┤
    │  PENDING    │  DECIDED   │  Pipeline completes → ALLOW         │
    │  PENDING    │  REVIEW    │  Pipeline completes → needs review  │
    │  PENDING    │  BLOCKED   │  Pipeline completes → BLOCK         │
    │  REVIEW     │  DECIDED   │  Human review → ALLOW               │
    │  REVIEW     │  BLOCKED   │  Human review → BLOCK               │
    └─────────────┴────────────┴─────────────────────────────────────┘

    INVARIANTS:
    • DECIDED, BLOCKED are terminal — no further transitions
    • Only REVIEW state allows human intervention
    • Each payment has exactly ONE final decision
```

---

## 3. Bảng so sánh Variants

| Aspect | **A: Monolith** | **B: Thread Pool** | **C: Kafka Pipeline** | **D: Per-Merchant** |
|--------|-----------------|--------------------|-----------------------|---------------------|
| **IPC** | None (in-process) | In-process (ExecutorService) | Kafka topics | RabbitMQ routing key |
| **Isolation** | ❌ None | ⚠️ Thread-level | ✅ Process-level | ✅ Process-level |
| **Fault tolerance** | ❌ Single point | ⚠️ Thread crash = task lost | ✅ At-least-once, DLQ | ✅ At-least-once, DLQ |
| **Scalability** | Vertical only | Vertical (thread count) | ✅ Horizontal per stage | ✅ Horizontal per merchant |
| **Backpressure** | ❌ None | ✅ Queue capacity + reject | ✅ Consumer lag | ✅ Prefetch count |
| **Latency** | ⚡ Lowest | ⚡ Low | ⚠️ Higher (network) | ⚠️ Higher (network) |
| **Complexity** | ⭐ Simple | ⭐⭐ Medium | ⭐⭐⭐ High | ⭐⭐⭐ High |
| **Use case** | Baseline, dev | CPU-bound scoring | Cloud-native, bursty | Multi-tenant fairness |

### 3.1 Variant A: Monolith (Baseline)

```
┌─────────────────────────────────────────────────────────┐
│                    SINGLE JVM PROCESS                   │
│  ┌─────────────────────────────────────────────────┐   │
│  │              Spring Boot Application            │   │
│  │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌──────────┐  │   │
│  │  │Ingest│→│Feat │→│Model│→│Rule │→│ Decision │  │   │
│  │  └─────┘ └─────┘ └─────┘ └─────┘ └──────────┘  │   │
│  │              (all in request thread)            │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 3.2 Variant B: Thread Pool

```
┌─────────────────────────────────────────────────────────┐
│                    SINGLE JVM PROCESS                   │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Request Thread                                 │   │
│  │  ┌─────┐ ┌─────┐                               │   │
│  │  │Ingest│→│Feat │──┐                           │   │
│  │  └─────┘ └─────┘  │                            │   │
│  │                   ▼                             │   │
│  │  ┌─────────────────────────────────────────┐   │   │
│  │  │         ThreadPoolExecutor              │   │   │
│  │  │  ┌───────┐ ┌───────┐ ┌───────┐         │   │   │
│  │  │  │Worker1│ │Worker2│ │Worker3│  (N)    │   │   │
│  │  │  │ Model │ │ Model │ │ Model │         │   │   │
│  │  │  └───────┘ └───────┘ └───────┘         │   │   │
│  │  └─────────────────┬───────────────────────┘   │   │
│  │                    │                            │   │
│  │                    ▼                            │   │
│  │  ┌─────┐ ┌──────────┐                          │   │
│  │  │Rule │→│ Decision │                          │   │
│  │  └─────┘ └──────────┘                          │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 3.3 Variant C: Kafka Pipeline

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         KAFKA PIPELINE (4 stages)                          │
│                                                                            │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │ Ingest   │    │ Feature  │    │  Model   │    │   Rule   │             │
│  │ Consumer │    │ Consumer │    │ Consumer │    │ Consumer │             │
│  │ (N pods) │    │ (N pods) │    │ (N pods) │    │ (N pods) │             │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘             │
│       │               │               │               │                    │
│       ▼               ▼               ▼               ▼                    │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │  topic:  │───▶│  topic:  │───▶│  topic:  │───▶│  topic:  │             │
│  │ ingest   │    │ feature  │    │  model   │    │   rule   │             │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘             │
│                                                        │                   │
│                                                        ▼                   │
│                                              ┌──────────────────┐          │
│                                              │ topic: risk.dlq  │          │
│                                              │ (poison messages)│          │
│                                              └──────────────────┘          │
└────────────────────────────────────────────────────────────────────────────┘
```

### 3.4 Variant D: Per-Merchant Routing

```
┌────────────────────────────────────────────────────────────────────────────┐
│                    RABBITMQ PER-MERCHANT ROUTING                           │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────┐     │
│  │                    Exchange: risk.direct                         │     │
│  └───────────────────────────────┬──────────────────────────────────┘     │
│                                  │                                         │
│         ┌────────────────────────┼────────────────────────┐               │
│         │                        │                        │               │
│         ▼                        ▼                        ▼               │
│  ┌─────────────┐          ┌─────────────┐          ┌─────────────┐       │
│  │ queue:m-1   │          │ queue:m-2   │          │ queue:m-N   │       │
│  │ routing=m-1 │          │ routing=m-2 │          │ routing=m-N │       │
│  └──────┬──────┘          └──────┬──────┘          └──────┬──────┘       │
│         │                        │                        │               │
│         ▼                        ▼                        ▼               │
│  ┌─────────────┐          ┌─────────────┐          ┌─────────────┐       │
│  │  Consumer   │          │  Consumer   │          │  Consumer   │       │
│  │ (merchant1) │          │ (merchant2) │          │ (merchantN) │       │
│  │ prefetch=1  │          │ prefetch=1  │          │ prefetch=1  │       │
│  └─────────────┘          └─────────────┘          └─────────────┘       │
│                                                                            │
│  BENEFIT: Per-merchant fairness — hot merchant không block others         │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Key Invariants

### 4.1 Idempotency

```
┌────────────────────────────────────────────────────────────────┐
│                    IDEMPOTENCY GUARANTEE                       │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  UNIQUE CONSTRAINT: (merchant_id, idempotency_key)             │
│                                                                │
│  Request 1: POST /authorize {idempotency_key: "abc"}           │
│       → INSERT payment → process pipeline → return decision    │
│                                                                │
│  Request 2: POST /authorize {idempotency_key: "abc"} (retry)   │
│       → SELECT existing payment → return cached decision       │
│       → NO re-processing, NO double-effect                     │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 4.2 Exactly-Once Effect

```
┌────────────────────────────────────────────────────────────────┐
│                  EXACTLY-ONCE EFFECT (per step)                │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  risk_events table tracks: (payment_id, step, status)          │
│                                                                │
│  Before processing step X:                                     │
│    1. Check if (payment_id, step, DONE) exists                 │
│    2. If exists → SKIP (already processed)                     │
│    3. If not → INSERT (payment_id, step, RUNNING)              │
│    4. Process step                                             │
│    5. UPDATE status = DONE                                     │
│                                                                │
│  On failure:                                                   │
│    → UPDATE status = FAILED                                    │
│    → Retry mechanism picks up                                  │
│    → After max retries → DLQ                                   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 4.3 Webhook Idempotency

```
┌────────────────────────────────────────────────────────────────┐
│                    WEBHOOK IDEMPOTENCY                         │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  Webhook payload includes:                                     │
│    {                                                           │
│      "payment_id": "p-123",                                    │
│      "idempotency_key": "idem-abc",                            │
│      "decision": "ALLOW",                                      │
│      "webhook_id": "wh-<uuid>"  ← unique per delivery attempt  │
│    }                                                           │
│                                                                │
│  Merchant should:                                              │
│    1. Check if webhook_id already processed                    │
│    2. If yes → return 200 OK (acknowledge)                     │
│    3. If no → process, store webhook_id, return 200 OK         │
│                                                                │
│  Our system:                                                   │
│    → Track webhook delivery in risk_events (step=WEBHOOK)      │
│    → Retry on 5xx, timeout                                     │
│    → DLQ after max retries                                     │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 5. Database Schema

```sql
-- Core payment record
CREATE TABLE payments (
  payment_id       TEXT PRIMARY KEY,
  merchant_id      TEXT NOT NULL,
  amount           BIGINT NOT NULL,
  currency         TEXT NOT NULL,
  idempotency_key  TEXT NOT NULL,
  state            TEXT NOT NULL CHECK (state IN ('PENDING','DECIDED','REVIEW','BLOCKED')),
  decision         TEXT NULL CHECK (decision IN ('ALLOW','REVIEW','BLOCK')),
  risk_score       NUMERIC NULL,
  created_at       TIMESTAMPTZ DEFAULT now(),
  updated_at       TIMESTAMPTZ DEFAULT now(),
  
  UNIQUE (merchant_id, idempotency_key)  -- idempotency guarantee
);

-- Audit log for pipeline steps
CREATE TABLE risk_events (
  id          BIGSERIAL PRIMARY KEY,
  payment_id  TEXT NOT NULL REFERENCES payments(payment_id),
  step        TEXT NOT NULL CHECK (step IN ('INGEST','FEATURE','MODEL','RULE','DECISION','WEBHOOK')),
  status      TEXT NOT NULL CHECK (status IN ('RUNNING','DONE','FAILED','RETRIED','SKIPPED')),
  detail      JSONB,
  created_at  TIMESTAMPTZ DEFAULT now(),
  
  UNIQUE (payment_id, step, status)  -- prevent duplicate events
);

-- Feature cache (optional, can use Redis)
CREATE TABLE feature_cache (
  key         TEXT PRIMARY KEY,
  value       JSONB NOT NULL,
  ttl_expire  TIMESTAMPTZ
);

-- Indexes for common queries
CREATE INDEX idx_payments_merchant ON payments(merchant_id);
CREATE INDEX idx_payments_state ON payments(state);
CREATE INDEX idx_risk_events_payment ON risk_events(payment_id);
CREATE INDEX idx_risk_events_step_status ON risk_events(step, status);
```

---

## 6. API Contracts

### 6.1 POST /payments/authorize

```json
// Request
{
  "payment_id": "p-123",
  "merchant_id": "m-9",
  "amount": 259900,
  "currency": "VND",
  "card_bin": "412345",
  "ip": "1.2.3.4",
  "device_id": "d-abc",
  "idempotency_key": "idem-<uuid>"
}

// Response (200 OK)
{
  "payment_id": "p-123",
  "decision": "ALLOW",  // or "REVIEW" or "BLOCK"
  "risk_score": 0.23,
  "state": "DECIDED"
}

// Response (409 Conflict - duplicate with different data)
{
  "error": "IDEMPOTENCY_CONFLICT",
  "message": "Request with same idempotency_key but different payload"
}
```

### 6.2 GET /payments/{id}/status

```json
// Response
{
  "payment_id": "p-123",
  "state": "PENDING",  // or "DECIDED", "REVIEW", "BLOCKED"
  "decision": null,    // or "ALLOW", "REVIEW", "BLOCK"
  "risk_score": null,
  "pipeline_status": {
    "INGEST": "DONE",
    "FEATURE": "DONE",
    "MODEL": "RUNNING",
    "RULE": "PENDING",
    "DECISION": "PENDING"
  }
}
```

### 6.3 POST /reviews/{payment_id}/decision

```json
// Request
{
  "decision": "ALLOW",  // or "BLOCK"
  "reviewer": "u-123"
}

// Response (200 OK)
{
  "payment_id": "p-123",
  "decision": "ALLOW",
  "state": "DECIDED",
  "reviewed_by": "u-123",
  "reviewed_at": "2024-01-15T10:30:00Z"
}
```

### 6.4 GET /metrics/stats

```json
// Response
{
  "throughput": {
    "rps_1m": 1850,
    "rps_5m": 1920
  },
  "latency": {
    "p50_ms": 45,
    "p95_ms": 120,
    "p99_ms": 180
  },
  "queue_depth": {
    "ingest": 12,
    "feature": 5,
    "model": 28,
    "rule": 3
  },
  "decisions": {
    "allow": 85420,
    "review": 1230,
    "block": 4350
  }
}
```

---

## 7. Tech Stack

| Layer | Technology |
|-------|------------|
| **Language** | Java 21 (Virtual Threads ready) |
| **Framework** | Spring Boot 3.3.x |
| **Build** | Gradle 8 (Kotlin DSL) |
| **Database** | PostgreSQL 16 + Spring Data JPA |
| **Migrations** | Flyway |
| **Cache** | Redis 7 |
| **Message Queue** | Kafka 3.7 (Variant C), RabbitMQ 3.13 (Variant D) |
| **Metrics** | Micrometer + Prometheus |
| **Tracing** | OpenTelemetry Java Agent + Jaeger |
| **Dashboards** | Grafana |
| **Benchmarking** | Gatling 3.10 |
| **Containerization** | Docker Compose |

---

## 8. Tiếp theo

Xem `docs/PLAN.md` để biết chi tiết các bước triển khai.
