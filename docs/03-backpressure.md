# Backpressure Pattern — Fraud Detection Gateway

## 1. Backpressure là gì?

### 1.1 Định nghĩa

**Backpressure** là cơ chế để producer biết rằng consumer đang quá tải và cần giảm tốc độ gửi.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    WITHOUT BACKPRESSURE                                │
└─────────────────────────────────────────────────────────────────────────┘

    Producer (fast)              Consumer (slow)
         │                            │
         │──── 1000 msg/s ───────────▶│ (can only process 100 msg/s)
         │                            │
         │                            │
         │                      ┌─────┴─────┐
         │                      │  BUFFER   │ ← Grows unbounded
         │                      │ OVERFLOW! │
         │                      └───────────┘
         │                            │
         │                            ▼
         │                      OutOfMemoryError
         │                      System crash


┌─────────────────────────────────────────────────────────────────────────┐
│                    WITH BACKPRESSURE                                   │
└─────────────────────────────────────────────────────────────────────────┘

    Producer                     Consumer
         │                            │
         │──── 1000 msg/s ───────────▶│
         │                            │
         │◀─── "Slow down!" ──────────│ (backpressure signal)
         │                            │
         │──── 100 msg/s ────────────▶│ (producer slows down)
         │                            │
         │                      ┌─────┴─────┐
         │                      │  BUFFER   │ ← Bounded, stable
         │                      │   OK ✓    │
         │                      └───────────┘
```

---

## 2. Backpressure Strategies

### 2.1 Drop

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         DROP STRATEGY                                   │
└─────────────────────────────────────────────────────────────────────────┘

    Khi buffer đầy → drop messages mới
    
    ┌─────────────────────────────────────────────────────────────────┐
    │  Buffer [●●●●●●●●●●] FULL                                       │
    │                                                                 │
    │  New message ──▶ DROP ✗                                        │
    │                                                                 │
    │  Use case: Real-time metrics, logs (acceptable loss)           │
    └─────────────────────────────────────────────────────────────────┘
```

### 2.2 Block

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         BLOCK STRATEGY                                  │
└─────────────────────────────────────────────────────────────────────────┘

    Khi buffer đầy → block producer
    
    ┌─────────────────────────────────────────────────────────────────┐
    │  Buffer [●●●●●●●●●●] FULL                                       │
    │                                                                 │
    │  Producer ──▶ WAIT... (blocked until space available)          │
    │                                                                 │
    │  Use case: Batch processing, no data loss                      │
    └─────────────────────────────────────────────────────────────────┘
```

### 2.3 Reject

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         REJECT STRATEGY                                 │
└─────────────────────────────────────────────────────────────────────────┘

    Khi buffer đầy → reject với error
    
    ┌─────────────────────────────────────────────────────────────────┐
    │  Buffer [●●●●●●●●●●] FULL                                       │
    │                                                                 │
    │  New request ──▶ HTTP 429 Too Many Requests                    │
    │                                                                 │
    │  Use case: API rate limiting                                   │
    └─────────────────────────────────────────────────────────────────┘
```

---

## 3. Implementation trong Fraud Detection

### 3.1 Thread Pool Backpressure (Variant B)

```java
@Configuration
public class ThreadPoolConfig {
    
    @Bean
    public ThreadPoolExecutor modelExecutor() {
        return new ThreadPoolExecutor(
                10,                          // corePoolSize
                20,                          // maxPoolSize
                60, TimeUnit.SECONDS,        // keepAliveTime
                new ArrayBlockingQueue<>(100), // bounded queue
                new ThreadPoolExecutor.CallerRunsPolicy() // backpressure
        );
    }
}
```

**RejectedExecutionHandler options:**

| Handler | Behavior | Use Case |
|---------|----------|----------|
| `CallerRunsPolicy` | Caller thread executes task | Slow down producer |
| `AbortPolicy` | Throw RejectedExecutionException | Fail fast |
| `DiscardPolicy` | Silently drop task | Acceptable loss |
| `DiscardOldestPolicy` | Drop oldest, add new | Latest wins |

### 3.2 HTTP 429 Response

```java
@RestController
public class PaymentController {
    
    private final ThreadPoolExecutor executor;
    
    @PostMapping("/payments/authorize")
    public ResponseEntity<?> authorize(@RequestBody AuthorizeRequest request) {
        // Check if system is overloaded
        if (executor.getQueue().remainingCapacity() < 10) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "5")
                    .body(Map.of(
                            "error", "System overloaded",
                            "retry_after_seconds", 5
                    ));
        }
        
        // Process normally
        return ResponseEntity.ok(paymentService.authorize(request));
    }
}
```

### 3.3 Kafka Consumer Backpressure (Variant C)

```java
@Configuration
public class KafkaConsumerConfig {
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> 
            kafkaListenerContainerFactory() {
        
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory());
        
        // Manual ack = natural backpressure
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        
        // Limit concurrent processing
        factory.setConcurrency(6);
        
        // Pause consumer when processing is slow
        factory.getContainerProperties().setIdleBetweenPolls(100);
        
        return factory;
    }
}
```

### 3.4 RabbitMQ Prefetch (Variant D)

```java
@Configuration
public class RabbitMQConfig {
    
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        
        SimpleRabbitListenerContainerFactory factory = 
                new SimpleRabbitListenerContainerFactory();
        
        factory.setConnectionFactory(connectionFactory);
        
        // Prefetch = 1: Only get 1 message at a time
        // Natural backpressure: won't get more until current is acked
        factory.setPrefetchCount(1);
        
        // Manual ack
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        
        return factory;
    }
}
```

---

## 4. Monitoring Backpressure

### 4.1 Key Metrics

```java
@Component
public class BackpressureMetrics {
    
    private final MeterRegistry registry;
    private final ThreadPoolExecutor executor;
    
    @PostConstruct
    public void registerMetrics() {
        // Queue depth
        Gauge.builder("executor.queue.size", executor, 
                e -> e.getQueue().size())
                .register(registry);
        
        // Queue remaining capacity
        Gauge.builder("executor.queue.remaining", executor, 
                e -> e.getQueue().remainingCapacity())
                .register(registry);
        
        // Active threads
        Gauge.builder("executor.active.threads", executor, 
                ThreadPoolExecutor::getActiveCount)
                .register(registry);
        
        // Rejected tasks
        Counter.builder("executor.rejected.total")
                .register(registry);
    }
}
```

### 4.2 Alerting Rules

```yaml
# Prometheus alerting rules
groups:
  - name: backpressure
    rules:
      - alert: QueueNearlyFull
        expr: executor_queue_remaining < 10
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Thread pool queue nearly full"
          
      - alert: HighRejectionRate
        expr: rate(executor_rejected_total[5m]) > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High task rejection rate"
```

---

## 5. Best Practices

### 5.1 Sizing Guidelines

| Component | Guideline |
|-----------|-----------|
| Thread pool core | 2 × CPU cores |
| Thread pool max | 4 × CPU cores |
| Queue size | 100-1000 (depends on latency tolerance) |
| Kafka prefetch | partitions × 2 |
| RabbitMQ prefetch | 1-10 (depends on processing time) |

### 5.2 Testing Backpressure

```java
@Test
void shouldRejectWhenOverloaded() {
    // Given - Fill up the queue
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> Thread.sleep(10000));
    }
    
    // When - Submit one more
    ResponseEntity<?> response = controller.authorize(createRequest());
    
    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
}
```

---

## 6. Tham khảo

- [Reactive Streams Backpressure](https://www.reactive-streams.org/)
- [Java ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
- [Kafka Consumer Backpressure](https://kafka.apache.org/documentation/#consumerconfigs)
