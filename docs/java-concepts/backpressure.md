# Java Concept: Backpressure

## 1. Tổng quan

**Backpressure** là cơ chế để producer (sender) biết consumer (receiver) đang quá tải và cần giảm tốc độ gửi.

## 2. Vấn đề: Không có Backpressure

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│ Producer │────▶│  Queue   │────▶│ Consumer │
│ 1000/s   │     │ (unbounded)    │ 100/s    │
└──────────┘     └──────────┘     └──────────┘
                      │
                      ▼
              Queue grows forever
                      │
                      ▼
                   OOM! 💥
```

**Hậu quả:**
- Memory exhaustion (OOM)
- Latency tăng vô hạn
- Cascading failures

## 3. Giải pháp: Bounded Queue + Rejection

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│ Producer │────▶│  Queue   │────▶│ Consumer │
│ 1000/s   │     │ (bounded)│     │ 100/s    │
└──────────┘     └────┬─────┘     └──────────┘
                      │
                 Queue full?
                      │
              ┌───────┴───────┐
              │               │
              ▼               ▼
         Reject task     Wait/Block
         (429 error)     (slow down)
```

## 4. Backpressure trong Thread Pool

### 4.1 Bounded Queue

```java
// ❌ Unbounded queue - no backpressure
new LinkedBlockingQueue<>()

// ✅ Bounded queue - backpressure when full
new ArrayBlockingQueue<>(500)
```

### 4.2 RejectedExecutionHandler

Khi queue đầy và pool đạt max size:

```java
// Option 1: Throw exception (convert to HTTP 429)
new RejectedExecutionHandler() {
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        throw new ServiceOverloadedException("Pool exhausted");
    }
}

// Option 2: CallerRunsPolicy - chạy trong thread gọi
// Tự động slow down producer
new ThreadPoolExecutor.CallerRunsPolicy()

// Option 3: Discard (không khuyến nghị cho business logic)
new ThreadPoolExecutor.DiscardPolicy()
```

### 4.3 Metrics để Monitor

```java
// Số task bị reject
Counter rejectedCounter = Counter.builder("pool.rejected.total")
    .register(registry);

// Queue depth
Gauge.builder("pool.queue.size", pool.getQueue(), Queue::size)
    .register(registry);

// Pool utilization
Gauge.builder("pool.active.threads", pool, ThreadPoolExecutor::getActiveCount)
    .register(registry);
```

## 5. HTTP 429 Too Many Requests

### 5.1 Response Format

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 5

{
    "error": "SERVICE_OVERLOADED",
    "message": "Too many requests. Please retry after 5 seconds.",
    "retry_after_seconds": 5
}
```

### 5.2 Client Handling

```java
// Client should implement exponential backoff
int retryCount = 0;
while (retryCount < MAX_RETRIES) {
    Response response = httpClient.post(request);
    
    if (response.status() == 429) {
        int retryAfter = response.header("Retry-After", 5);
        Thread.sleep(retryAfter * 1000L * (1 << retryCount));
        retryCount++;
    } else {
        return response;
    }
}
throw new ServiceUnavailableException("Max retries exceeded");
```

## 6. Trong Fraud Detection Gateway

### 6.1 Custom Rejection Handler

```java
public class BackpressureRejectionHandler implements RejectedExecutionHandler {
    
    private final Counter rejectedCounter;
    
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        rejectedCounter.increment();
        
        log.warn("Thread pool exhausted! pool={}, queue={}", 
                executor.getPoolSize(), executor.getQueue().size());
        
        throw new ThreadPoolExhaustedException(
                "Model scoring pool exhausted. Pool: " + executor.getPoolSize() +
                ", Queue: " + executor.getQueue().size()
        );
    }
}
```

### 6.2 Exception Handler → HTTP 429

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ThreadPoolExhaustedException.class)
    public ResponseEntity<Map<String, Object>> handlePoolExhausted(
            ThreadPoolExhaustedException ex) {
        
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(Map.of(
                    "error", "SERVICE_OVERLOADED",
                    "message", ex.getMessage(),
                    "retry_after_seconds", 5
                ));
    }
}
```

### 6.3 Flow Diagram

```
                    ┌─────────────────────────────────────────────────────┐
                    │              BACKPRESSURE FLOW                      │
                    └─────────────────────────────────────────────────────┘

    POST /authorize
         │
         ▼
    ┌─────────────────┐
    │ PaymentService  │
    │   .authorize()  │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │ AsyncPipeline   │
    │ .scoreAsync()   │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐     ┌─────────────────┐
    │ ThreadPool      │────▶│ Queue full?     │
    │ .submit()       │     └────────┬────────┘
    └─────────────────┘              │
                            ┌────────┴────────┐
                            │                 │
                            ▼                 ▼
                    ┌───────────┐     ┌───────────────┐
                    │ Execute   │     │ Reject        │
                    │ in pool   │     │ (throw ex)    │
                    └───────────┘     └───────┬───────┘
                                              │
                                              ▼
                                    ┌─────────────────┐
                                    │ ExceptionHandler│
                                    │ → HTTP 429      │
                                    └─────────────────┘
```

## 7. Monitoring Backpressure

### 7.1 Key Metrics

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `pool.rejected.total` | Tasks rejected | > 0 |
| `pool.queue.size` | Queue depth | > 80% capacity |
| `pool.active.threads` | Active threads | = max pool size |
| `http.429.total` | 429 responses | > 1% of requests |

### 7.2 Grafana Dashboard

```
┌─────────────────────────────────────────────────────────────┐
│                    THREAD POOL HEALTH                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Active Threads: ████████░░ 8/10                           │
│  Queue Depth:    ██████████████░░░░░░ 350/500              │
│  Rejected/min:   ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁ 0                    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Queue Depth Over Time                               │   │
│  │     500 ┼───────────────────────────────────────    │   │
│  │         │                    ╱╲                     │   │
│  │     250 ┼──────────────────╱──╲────────────────    │   │
│  │         │                 ╱    ╲                    │   │
│  │       0 ┼────────────────╱──────╲──────────────    │   │
│  │         └───────────────────────────────────────    │   │
│  │          00:00    00:15    00:30    00:45    01:00  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 8. Best Practices

### 8.1 Sizing Queue

```java
// Queue size = expected_latency * throughput
// Ví dụ: 100ms latency, 1000 RPS → queue = 100
int queueSize = (int) (expectedLatencyMs / 1000.0 * targetRps);
```

### 8.2 Graceful Degradation

```java
// Khi bị reject, có thể:
// 1. Return cached/default result
// 2. Skip non-critical steps
// 3. Route to fallback service

public BigDecimal scoreWithFallback(PipelineContext context) {
    try {
        return asyncModelStep.scoreAsync(context).get(timeout);
    } catch (ThreadPoolExhaustedException e) {
        log.warn("Pool exhausted, using default score");
        return DEFAULT_RISK_SCORE;  // Graceful degradation
    }
}
```

### 8.3 Circuit Breaker Pattern

```java
// Kết hợp với Circuit Breaker để tránh cascading failures
@CircuitBreaker(name = "modelScoring", fallbackMethod = "fallbackScore")
public BigDecimal score(PipelineContext context) {
    return asyncModelStep.scoreAsync(context).join();
}

public BigDecimal fallbackScore(PipelineContext context, Exception e) {
    return DEFAULT_RISK_SCORE;
}
```

## 9. Tham khảo

- [Reactive Streams Specification](https://www.reactive-streams.org/)
- [Backpressure in Distributed Systems](https://mechanical-sympathy.blogspot.com/)
- [Netflix Hystrix](https://github.com/Netflix/Hystrix) (Circuit Breaker)
