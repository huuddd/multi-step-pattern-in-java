# Fraud Detection Gateway — Source Code

## Project Structure

```
source/
├── common/                 # Shared DTOs, domain entities, utils
├── api-service/            # Spring Boot REST API
├── variants/
│   ├── a_monolith/         # Variant A: Single-threaded baseline
│   ├── b_thread_pool/      # Variant B: ExecutorService for model scoring
│   ├── c_kafka_pipeline/   # Variant C: Kafka-based staged pipeline
│   └── d_per_merchant/     # Variant D: RabbitMQ per-merchant routing
├── bench/                  # Gatling benchmark simulations
├── docker-compose.yml      # Infrastructure services
├── Makefile                # Build & run commands
└── build.gradle.kts        # Gradle multi-module build
```

## Prerequisites

- Java 21+
- Docker & Docker Compose
- Gradle 8+

## Quick Start

```bash
# Start infrastructure (PostgreSQL, Redis, Kafka, RabbitMQ, Prometheus)
make up

# Seed demo merchants and rules
make seed

# Run the application (default: Variant A)
make run

# Run benchmarks
make bench:uniform
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | fraud | Database name |
| `DB_USER` | fraud | Database user |
| `DB_PASSWORD` | fraud | Database password |
| `REDIS_HOST` | localhost | Redis host |
| `KAFKA_BOOTSTRAP` | localhost:9092 | Kafka bootstrap servers |
| `RABBITMQ_HOST` | localhost | RabbitMQ host |

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/payments/authorize` | Submit payment for fraud check |
| GET | `/payments/{id}/status` | Get payment status |
| POST | `/reviews/{payment_id}/decision` | Human review decision |
| GET | `/metrics/stats` | Get system metrics |

## Metrics

Prometheus metrics available at `/actuator/prometheus`:

- `authorize_requests_total` — Total authorize requests
- `step_latency_ms{stage}` — Per-stage latency
- `queue_depth{stage}` — Queue depth per stage
- `decision_total{type}` — Decision breakdown

## Logs

JSON structured logs with fields:
- `payment_id`
- `merchant_id`
- `step`
- `attempt`
