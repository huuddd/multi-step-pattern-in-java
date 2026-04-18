# Fraud Detection Gateway — Hướng dẫn từ A-Z

## 📋 Mục lục

1. [Giới thiệu](#1-giới-thiệu)
2. [Yêu cầu hệ thống](#2-yêu-cầu-hệ-thống)
3. [Cài đặt](#3-cài-đặt)
4. [Chạy ứng dụng](#4-chạy-ứng-dụng)
5. [Test API](#5-test-api)
6. [Chạy Benchmark](#6-chạy-benchmark)
7. [Xem Metrics & Traces](#7-xem-metrics--traces)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. Giới thiệu

**Fraud Detection Gateway** là hệ thống phát hiện gian lận thanh toán với 4 variants:

| Variant | Mô tả | Khi nào dùng |
|---------|-------|--------------|
| **A** | Synchronous | Learning, prototype |
| **B** | Thread Pool | Production đơn giản |
| **C** | Kafka Pipeline | High throughput |
| **D** | RabbitMQ Per-Merchant | Multi-tenant SaaS |

### Pipeline Flow

```
POST /payments/authorize
        │
        ▼
   ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
   │ INGEST  │───▶│ FEATURE │───▶│  MODEL  │───▶│  RULE   │
   └─────────┘    └─────────┘    └─────────┘    └─────────┘
                                                      │
                                                      ▼
                                              ┌─────────────┐
                                              │  DECISION   │
                                              │ ALLOW/BLOCK │
                                              └─────────────┘
```

---

## 2. Yêu cầu hệ thống

### Bắt buộc

| Tool | Version | Kiểm tra |
|------|---------|----------|
| Java | 21+ | `java -version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | 2.20+ | `docker compose version` |

### Khuyến nghị

- RAM: 8GB+
- Disk: 10GB free
- OS: Windows 10/11, macOS, Linux

---

## 3. Cài đặt

### Bước 1: Clone repository

```bash
git clone <repository-url>
cd multi-step-pattern-in-java/source
```

### Bước 2: Build project

```bash
# Windows
.\gradlew.bat build -x test

# macOS/Linux
./gradlew build -x test
```

### Bước 3: Start infrastructure

```bash
# Start PostgreSQL + Redis (cơ bản)
docker compose up -d postgres redis

# Hoặc start tất cả (bao gồm Kafka, RabbitMQ, Prometheus, Grafana, Jaeger)
docker compose --profile kafka --profile rabbitmq --profile observability up -d
```

### Bước 4: Verify services

```bash
# Check containers
docker compose ps

# Expected output:
# fraud-postgres    running    0.0.0.0:5432->5432/tcp
# fraud-redis       running    0.0.0.0:6379->6379/tcp
```

---

## 4. Chạy ứng dụng

### Variant A (Synchronous - Default)

```bash
# Windows
.\gradlew.bat :api-service:bootRun

# macOS/Linux
./gradlew :api-service:bootRun
```

### Variant B (Thread Pool)

```bash
./gradlew :api-service:bootRun --args='--spring.profiles.active=thread-pool'
```

### Variant C (Kafka Pipeline)

```bash
# Start Kafka first
docker compose --profile kafka up -d

# Run app
./gradlew :api-service:bootRun --args='--spring.profiles.active=kafka'
```

### Variant D (RabbitMQ Per-Merchant)

```bash
# Start RabbitMQ first
docker compose --profile rabbitmq up -d

# Run app
./gradlew :api-service:bootRun --args='--spring.profiles.active=rabbitmq'
```

### Verify app is running

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

---

## 5. Test API

### 5.1 Authorize Payment

```bash
curl -X POST http://localhost:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "payment_id": "p-test-001",
    "merchant_id": "m-001",
    "amount": 500000,
    "currency": "VND",
    "card_bin": "412345",
    "ip": "1.2.3.4",
    "device_id": "d-001",
    "idempotency_key": "idem-001"
  }'
```

**Expected Response:**

```json
{
  "payment_id": "p-test-001",
  "decision": "ALLOW",
  "risk_score": 0.25,
  "state": "DECIDED",
  "processed_at": "2024-01-15T10:30:00Z"
}
```

### 5.2 Test Blacklisted BIN

```bash
curl -X POST http://localhost:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -d '{
    "payment_id": "p-test-002",
    "merchant_id": "m-001",
    "amount": 500000,
    "currency": "VND",
    "card_bin": "000000",
    "ip": "1.2.3.4",
    "device_id": "d-002",
    "idempotency_key": "idem-002"
  }'
```

**Expected:** `"decision": "BLOCK"`

### 5.3 Get Payment Status

```bash
curl http://localhost:8080/payments/p-test-001/status
```

### 5.4 API Endpoints Summary

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| POST | `/payments/authorize` | Submit payment |
| GET | `/payments/{id}/status` | Get status |
| POST | `/reviews/{id}/decision` | Human review |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Metrics |

---

## 6. Chạy Benchmark

### 6.1 Start Observability Stack

```bash
docker compose --profile observability up -d
```

### 6.2 Run Benchmarks

```bash
# Uniform load (1000 RPS)
./gradlew :bench:gatlingRun-simulations.UniformSimulation

# Hot merchant (70% to one merchant)
./gradlew :bench:gatlingRun-simulations.HotMerchantSimulation

# Model slowdown
./gradlew :bench:gatlingRun-simulations.ModelSlowdownSimulation

# Fault injection
./gradlew :bench:gatlingRun-simulations.FaultInjectionSimulation
```

### 6.3 View Results

- **Gatling Report:** `source/bench/build/reports/gatling/`
- **Grafana:** http://localhost:3000 (admin/admin)
- **Jaeger:** http://localhost:16686

---

## 7. Xem Metrics & Traces

### 7.1 Grafana Dashboard

1. Mở http://localhost:3000
2. Login: admin / admin
3. Vào **Dashboards** → **Fraud Detection Dashboard**

**Panels:**
- Throughput (RPS)
- Error Rate
- p99 Latency
- Decision Breakdown
- Queue Depth

### 7.2 Jaeger Tracing

1. Mở http://localhost:16686
2. Chọn Service: `fraud-api`
3. Click **Find Traces**
4. Click vào trace để xem chi tiết

### 7.3 Prometheus Queries

Mở http://localhost:9090 và thử các queries:

```promql
# Throughput
rate(pipeline_authorize_total[1m])

# p99 Latency
histogram_quantile(0.99, rate(pipeline_duration_seconds_bucket[1m]))

# Error Rate
rate(pipeline_errors_total[1m]) / rate(pipeline_authorize_total[1m])
```

---

## 8. Troubleshooting

### 8.1 Port đã được sử dụng

```bash
# Kiểm tra port
netstat -ano | findstr :8080

# Kill process (Windows)
taskkill /PID <PID> /F
```

### 8.2 Database connection failed

```bash
# Check PostgreSQL
docker compose logs postgres

# Restart PostgreSQL
docker compose restart postgres
```

### 8.3 Kafka connection failed

```bash
# Check Kafka
docker compose --profile kafka logs kafka

# Restart Kafka
docker compose --profile kafka restart kafka
```

### 8.4 Build failed

```bash
# Clean build
./gradlew clean build -x test

# Check Java version
java -version  # Must be 21+
```

### 8.5 Out of memory

```bash
# Increase heap size
export JAVA_OPTS="-Xmx2g"
./gradlew :api-service:bootRun
```

---

## 📁 Project Structure

```
source/
├── common/                 # Shared DTOs, domain entities
├── api-service/            # Spring Boot REST API
│   ├── src/main/java/
│   │   └── com/fraud/api/
│   │       ├── controller/     # REST controllers
│   │       ├── service/        # Business logic
│   │       ├── pipeline/       # Pipeline steps
│   │       ├── kafka/          # Kafka consumers (Variant C)
│   │       └── rabbitmq/       # RabbitMQ consumers (Variant D)
│   └── src/main/resources/
│       └── application.yml     # Configuration
├── bench/                  # Gatling benchmarks
├── grafana/                # Grafana provisioning
├── docker-compose.yml      # Infrastructure
└── build.gradle.kts        # Build config
```

---

## 📚 Documentation

| Document | Mô tả |
|----------|-------|
| `docs/PLAN.md` | Implementation plan |
| `docs/REPORT.md` | Technical report |
| `docs/variants/*.md` | Variant design notes |
| `docs/java-concepts/*.md` | Java concept notes |

---

## 🔗 URLs Summary

| Service | URL | Credentials |
|---------|-----|-------------|
| API | http://localhost:8080 | - |
| Grafana | http://localhost:3000 | admin/admin |
| Jaeger | http://localhost:16686 | - |
| Prometheus | http://localhost:9090 | - |
| RabbitMQ UI | http://localhost:15672 | fraud/fraud |
