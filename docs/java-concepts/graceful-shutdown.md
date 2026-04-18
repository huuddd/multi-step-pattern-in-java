# Java Concept: Graceful Shutdown

## 1. Tổng quan

**Graceful shutdown** là quá trình dừng application một cách "nhẹ nhàng":
1. Ngừng nhận requests mới
2. Chờ requests đang xử lý hoàn thành
3. Cleanup resources
4. Exit

## 2. Vấn đề: Hard Shutdown

```
┌─────────────────────────────────────────────────────────────┐
│                    HARD SHUTDOWN                            │
└─────────────────────────────────────────────────────────────┘

    Request 1: ████████░░░░░░░░ (50% done)
    Request 2: ████████████░░░░ (75% done)
    Request 3: ██░░░░░░░░░░░░░░ (10% done)
                        │
                        ▼
                   SIGKILL 💀
                        │
                        ▼
    ┌─────────────────────────────────────────────────────────┐
    │ - Request 1: LOST (payment created but not decided)    │
    │ - Request 2: LOST (decision made but webhook not sent) │
    │ - Request 3: LOST (not even started)                   │
    │ - Database: inconsistent state                         │
    │ - Client: timeout, retry → duplicate?                  │
    └─────────────────────────────────────────────────────────┘
```

## 3. Giải pháp: Graceful Shutdown

```
┌─────────────────────────────────────────────────────────────┐
│                   GRACEFUL SHUTDOWN                         │
└─────────────────────────────────────────────────────────────┘

    SIGTERM received
         │
         ▼
    ┌─────────────────┐
    │ 1. Stop accepting│
    │    new requests  │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐     Request 1: ████████████████ ✓
    │ 2. Wait for     │     Request 2: ████████████████ ✓
    │    in-flight    │     Request 3: ████████████████ ✓
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │ 3. Cleanup      │
    │    resources    │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │ 4. Exit cleanly │
    └─────────────────┘
```

## 4. Spring Boot Graceful Shutdown

### 4.1 Configuration

```yaml
# application.yml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### 4.2 Shutdown Phases

1. **SIGTERM** received
2. Spring stops accepting new HTTP requests
3. Wait for in-flight requests (up to timeout)
4. Call `@PreDestroy` methods
5. Close ApplicationContext
6. JVM exits

## 5. Thread Pool Graceful Shutdown

### 5.1 shutdown() vs shutdownNow()

```java
// shutdown() - graceful
executor.shutdown();  // Stop accepting new tasks
                      // Let running tasks complete
                      // Let queued tasks execute

// shutdownNow() - forceful
executor.shutdownNow();  // Stop accepting new tasks
                         // Interrupt running tasks
                         // Return queued tasks (not executed)
```

### 5.2 awaitTermination()

```java
executor.shutdown();

// Wait up to 30 seconds for tasks to complete
if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
    log.warn("Pool did not terminate in time, forcing shutdown");
    executor.shutdownNow();
    
    // Wait again for interrupted tasks
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        log.error("Pool did not terminate after force shutdown");
    }
}
```

### 5.3 Complete Pattern

```java
@Component
public class ThreadPoolShutdownHandler {
    
    private final ThreadPoolExecutor executor;
    
    @PreDestroy
    public void shutdown() {
        log.info("Initiating graceful shutdown of thread pool");
        
        // 1. Stop accepting new tasks
        executor.shutdown();
        
        try {
            // 2. Wait for running tasks
            log.info("Waiting for {} active tasks to complete", 
                    executor.getActiveCount());
            
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Timeout waiting for tasks, forcing shutdown");
                
                // 3. Force shutdown
                List<Runnable> notExecuted = executor.shutdownNow();
                log.warn("{} tasks were not executed", notExecuted.size());
                
                // 4. Wait for interrupted tasks
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            log.warn("Shutdown interrupted, forcing immediate shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("Thread pool shutdown complete");
    }
}
```

## 6. In-Flight Request Tracking

### 6.1 AtomicInteger Counter

```java
@Component
public class InFlightTracker {
    
    private final AtomicInteger inFlight = new AtomicInteger(0);
    
    public void increment() {
        inFlight.incrementAndGet();
    }
    
    public void decrement() {
        inFlight.decrementAndGet();
    }
    
    public int get() {
        return inFlight.get();
    }
    
    public boolean hasInFlight() {
        return inFlight.get() > 0;
    }
}
```

### 6.2 Wait for In-Flight

```java
@PreDestroy
public void shutdown() {
    // Wait for in-flight requests
    int maxWaitSeconds = 30;
    int waited = 0;
    
    while (inFlightTracker.hasInFlight() && waited < maxWaitSeconds) {
        log.info("Waiting for {} in-flight requests", inFlightTracker.get());
        Thread.sleep(1000);
        waited++;
    }
    
    if (inFlightTracker.hasInFlight()) {
        log.warn("Shutdown with {} in-flight requests", inFlightTracker.get());
    }
}
```

## 7. Trong Fraud Detection Gateway

### 7.1 Shutdown Configuration

```java
@Configuration
public class ShutdownConfig {
    
    @Bean
    public GracefulShutdownHandler gracefulShutdownHandler(
            @Qualifier("modelScoringExecutor") ThreadPoolExecutor executor,
            InFlightRequestsTracker inFlightTracker) {
        return new GracefulShutdownHandler(executor, inFlightTracker);
    }
}
```

### 7.2 Shutdown Handler

```java
@Component
@Slf4j
public class GracefulShutdownHandler {
    
    private final ThreadPoolExecutor executor;
    private final InFlightRequestsTracker inFlightTracker;
    
    @Value("${shutdown.timeout-seconds:30}")
    private int shutdownTimeoutSeconds;
    
    @PreDestroy
    public void shutdown() {
        log.info("=== GRACEFUL SHUTDOWN INITIATED ===");
        log.info("In-flight requests: {}", inFlightTracker.get());
        log.info("Active pool threads: {}", executor.getActiveCount());
        log.info("Queued tasks: {}", executor.getQueue().size());
        
        // 1. Stop accepting new tasks
        executor.shutdown();
        
        try {
            // 2. Wait for completion
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown after timeout");
                List<Runnable> dropped = executor.shutdownNow();
                log.warn("Dropped {} queued tasks", dropped.size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("=== GRACEFUL SHUTDOWN COMPLETE ===");
        log.info("Final in-flight: {}", inFlightTracker.get());
    }
}
```

## 8. Kubernetes Integration

### 8.1 Pod Lifecycle

```yaml
# deployment.yaml
spec:
  containers:
  - name: fraud-gateway
    lifecycle:
      preStop:
        exec:
          command: ["sh", "-c", "sleep 5"]  # Allow LB to drain
    terminationGracePeriodSeconds: 60  # Total time for shutdown
```

### 8.2 Readiness Probe

```java
@Component
public class ReadinessIndicator implements HealthIndicator {
    
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    @PreDestroy
    public void markShuttingDown() {
        shuttingDown.set(true);
    }
    
    @Override
    public Health health() {
        if (shuttingDown.get()) {
            return Health.down()
                    .withDetail("reason", "Shutting down")
                    .build();
        }
        return Health.up().build();
    }
}
```

## 9. Shutdown Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    COMPLETE SHUTDOWN FLOW                               │
└─────────────────────────────────────────────────────────────────────────┘

    SIGTERM
       │
       ▼
┌──────────────────┐
│ Mark as          │ ──▶ Readiness probe returns DOWN
│ "shutting down"  │     Load balancer stops sending traffic
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ executor         │ ──▶ No new tasks accepted
│ .shutdown()      │     Queue continues processing
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ awaitTermination │ ──▶ Wait for active tasks
│ (30 seconds)     │     Monitor in-flight counter
└────────┬─────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
 Completed  Timeout
    │         │
    │         ▼
    │    ┌──────────────────┐
    │    │ shutdownNow()    │ ──▶ Interrupt running tasks
    │    │ + log dropped    │     Log dropped tasks
    │    └────────┬─────────┘
    │             │
    └──────┬──────┘
           │
           ▼
┌──────────────────┐
│ Close DB pools   │
│ Close Redis      │
│ Flush metrics    │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ JVM Exit (0)     │
└──────────────────┘
```

## 10. Best Practices

### 10.1 Timeout Configuration

```yaml
# Rule of thumb:
# shutdown_timeout > max_request_latency + buffer

# If p99 latency = 5s, set timeout = 30s
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### 10.2 Log Shutdown Progress

```java
@PreDestroy
public void shutdown() {
    log.info("Shutdown: in-flight={}, queue={}, active={}",
            inFlightTracker.get(),
            executor.getQueue().size(),
            executor.getActiveCount());
}
```

### 10.3 Metrics on Shutdown

```java
// Record metrics before shutdown
meterRegistry.counter("shutdown.in_flight").increment(inFlightTracker.get());
meterRegistry.counter("shutdown.queued").increment(executor.getQueue().size());
```

## 11. Tham khảo

- [Spring Boot Graceful Shutdown](https://docs.spring.io/spring-boot/docs/current/reference/html/web.html#web.graceful-shutdown)
- [Kubernetes Pod Lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)
- [ExecutorService Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html)
