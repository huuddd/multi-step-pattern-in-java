# Variant B: Thread Pool (ExecutorService)

## Tổng quan

Variant B cải tiến Variant A bằng cách chạy **Model Scoring** trong **dedicated thread pool**. Các bước khác vẫn chạy sync trong request thread.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         SINGLE JVM PROCESS                              │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                     HTTP Request Thread                           │  │
│  │                                                                   │  │
│  │  ┌─────────┐  ┌─────────┐                 ┌─────────┐  ┌────────┐ │  │
│  │  │ INGEST  │─▶│ FEATURE │─────────┐      │  RULE   │─▶│DECISION│ │  │
│  │  └─────────┘  └─────────┘         │      └────▲────┘  └────────┘ │  │
│  │                                   │           │                   │  │
│  └───────────────────────────────────┼───────────┼───────────────────┘  │
│                                      │           │                      │
│                                      ▼           │                      │
│  ┌───────────────────────────────────────────────┼───────────────────┐  │
│  │              MODEL SCORING THREAD POOL        │                   │  │
│  │  ┌─────────────────────────────────────────┐  │                   │  │
│  │  │ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ │  │                   │  │
│  │  │ │Thread1│ │Thread2│ │Thread3│ │Thread4│ │──┘                   │  │
│  │  │ └───────┘ └───────┘ └───────┘ └───────┘ │                      │  │
│  │  │         ┌─────────────────────┐         │                      │  │
│  │  │         │   Bounded Queue     │         │                      │  │
│  │  │         │   (500 tasks max)   │         │                      │  │
│  │  │         └─────────────────────┘         │                      │  │
│  │  └─────────────────────────────────────────┘                      │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Key Components

### 1. ThreadPoolExecutor Configuration

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    cores,              // corePoolSize = CPU cores
    cores * 2,          // maxPoolSize = 2x cores (burst)
    60, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(500),  // bounded queue
    namedThreadFactory("model-scorer"),
    new BackpressureRejectionHandler()
);
```

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| corePoolSize | CPU cores | Model scoring is CPU-bound |
| maxPoolSize | 2x cores | Handle burst traffic |
| queueCapacity | 500 | Bounded for backpressure |
| keepAliveTime | 60s | Scale down when idle |

### 2. Async Model Scoring

```java
public CompletableFuture<BigDecimal> scoreAsync(PipelineContext context) {
    return CompletableFuture.supplyAsync(
        () -> calculateRiskScore(context),
        modelScoringExecutor
    ).orTimeout(5, TimeUnit.SECONDS);
}
```

### 3. Backpressure Flow

```
Request arrives
      │
      ▼
┌─────────────────┐
│ Submit to pool  │
└────────┬────────┘
         │
    Pool full?
         │
    ┌────┴────┐
    │         │
    ▼         ▼
 Execute   Reject
    │         │
    │         ▼
    │    ┌─────────────────┐
    │    │ HTTP 429        │
    │    │ Retry-After: 5s │
    │    └─────────────────┘
    │
    ▼
 Return score
```

### 4. Graceful Shutdown

```java
@PreDestroy
public void shutdown() {
    executor.shutdown();
    if (!executor.awaitTermination(30, SECONDS)) {
        executor.shutdownNow();
    }
}
```

## So sánh với Variant A

| Aspect | Variant A (Sync) | Variant B (Async Pool) |
|--------|------------------|------------------------|
| Model execution | Request thread | Dedicated pool |
| CPU utilization | Lower | Higher (parallel) |
| Backpressure | None (OOM risk) | Bounded queue + 429 |
| Latency under load | Degrades | More stable |
| Complexity | Simple | Moderate |
| Fault isolation | None | Partial (pool isolated) |

## Pros & Cons

### ✅ Pros
- **Better CPU utilization** — parallel model scoring
- **Backpressure** — bounded queue prevents OOM
- **Graceful degradation** — 429 instead of crash
- **Configurable** — tune pool size per environment

### ❌ Cons
- **Still single process** — no horizontal scaling
- **Partial isolation** — other steps still sync
- **Complexity** — thread pool tuning required
- **No retry** — failed tasks not automatically retried

## When to Use

- **Medium traffic** — 1000-5000 RPS
- **CPU-bound bottleneck** — model scoring is slow
- **Single machine** — can't distribute yet
- **Need backpressure** — protect from overload

## Configuration

```yaml
# application.yml
thread-pool:
  model-scoring:
    core-size: 4          # = CPU cores
    max-size: 8           # = 2x cores
    queue-capacity: 500   # bounded queue
    keep-alive-seconds: 60

shutdown:
  timeout-seconds: 30
  force-timeout-seconds: 10

pipeline:
  model:
    timeout-ms: 5000
```

## Metrics

| Metric | Description |
|--------|-------------|
| `executor.pool.size` | Current pool size |
| `executor.active` | Active threads |
| `executor.queue.size` | Queued tasks |
| `executor.completed` | Completed tasks |
| `model.scoring.async.duration` | Async scoring time |
| `pool.rejected.total` | Rejected tasks |

## Files

```
source/api-service/src/main/java/com/fraud/api/
├── config/
│   ├── ThreadPoolConfig.java          # Pool configuration
│   ├── GracefulShutdownHandler.java   # Shutdown handling
│   └── MetricsConfig.java             # In-flight tracking
├── pipeline/
│   ├── AsyncFraudDetectionPipeline.java  # Async orchestrator
│   └── steps/
│       └── AsyncModelStep.java        # Async model scoring
└── exception/
    └── GlobalExceptionHandler.java    # 429 handling
```

## Running

```bash
# Start infrastructure
make up

# Run with async pipeline
PIPELINE_VARIANT=async make run

# Benchmark
make bench:uniform

# View metrics
curl http://localhost:8080/actuator/prometheus | grep executor
```

## Benchmark Results

See `docs/benchmark/B-uniform.txt` after running:

```bash
make bench:uniform
```

### Expected Improvements over Variant A

| Metric | Variant A | Variant B | Improvement |
|--------|-----------|-----------|-------------|
| Throughput | ~1500 RPS | ~2200 RPS | +47% |
| p95 latency | ~80ms | ~45ms | -44% |
| p99 latency | ~250ms | ~120ms | -52% |
| Error rate | ~0.5% | ~0.04% | -92% |

## Lessons Learned

1. **Bounded queue là bắt buộc** — unbounded queue = OOM
2. **Pool size = CPU cores** cho CPU-bound tasks
3. **Timeout là bắt buộc** — tránh hanging requests
4. **Graceful shutdown** — tránh mất data khi restart
5. **Metrics** — monitor pool health liên tục
