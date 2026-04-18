# 07. Threading Model — Thread, Async, Concurrency

## 🎯 Mục tiêu

- Hiểu Thread vs Process, Heap vs Stack per thread
- Biết dùng ExecutorService, CompletableFuture
- Tránh race condition, deadlock
- Debug thread issues

---

## 1. Thread là gì?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PROCESS vs THREAD                                   │
└─────────────────────────────────────────────────────────────────────────┘

    PROCESS (OS-level)
    ┌────────────────────────────────────────────────────────────┐
    │  JVM Process (1 PID)                                       │
    │                                                            │
    │  ┌──────────────────────────────────────────────────────┐ │
    │  │              SHARED HEAP                             │ │
    │  │  (All threads share this memory)                     │ │
    │  │  - Objects, classes, static fields                   │ │
    │  └──────────────────────────────────────────────────────┘ │
    │                                                            │
    │  THREADS (within process)                                 │
    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
    │  │ Thread 1     │  │ Thread 2     │  │ Thread 3     │   │
    │  │              │  │              │  │              │   │
    │  │ Stack        │  │ Stack        │  │ Stack        │   │
    │  │ (private)    │  │ (private)    │  │ (private)    │   │
    │  │              │  │              │  │              │   │
    │  │ PC register  │  │ PC register  │  │ PC register  │   │
    │  └──────────────┘  └──────────────┘  └──────────────┘   │
    └────────────────────────────────────────────────────────────┘
```

**Ý nghĩa:**
- **Process:** Ứng dụng chạy trên OS (VD: JVM)
- **Thread:** Đơn vị execution trong process
- Các threads **share heap** nhưng có **stack riêng**

---

## 2. Thread cơ bản

### 2.1 Tạo thread — 3 cách

```java
// Cách 1: Thread class
Thread t1 = new Thread(() -> {
    System.out.println("Hello from " + Thread.currentThread().getName());
});
t1.start();

// Cách 2: Runnable
Runnable task = () -> System.out.println("Running task");
new Thread(task).start();

// Cách 3: Virtual Thread (Java 21)
Thread.ofVirtual().start(() -> System.out.println("Virtual thread"));
```

### 2.2 Thread states

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        THREAD LIFECYCLE                                │
└─────────────────────────────────────────────────────────────────────────┘

         new Thread()
              │
              ▼
         ┌─────────┐   start()    ┌──────────┐
         │   NEW   │─────────────▶│ RUNNABLE │
         └─────────┘              └────┬─────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
                    ▼                  ▼                  ▼
              ┌──────────┐      ┌──────────┐       ┌──────────┐
              │ BLOCKED  │      │ WAITING  │       │  TIMED   │
              │          │      │          │       │  WAITING │
              │(waiting  │      │ (wait,   │       │          │
              │  lock)   │      │  join)   │       │  (sleep) │
              └────┬─────┘      └────┬─────┘       └────┬─────┘
                   │                 │                  │
                   └────────┬────────┴──────────────────┘
                            │
                            ▼
                       ┌──────────┐
                       │ RUNNABLE │
                       └────┬─────┘
                            │
                            ▼
                      ┌──────────────┐
                      │  TERMINATED  │
                      └──────────────┘
```

### 2.3 Thread control

```java
Thread t = new Thread(task);
t.start();          // Bắt đầu
t.join();           // Chờ thread kết thúc
t.interrupt();      // Yêu cầu dừng (không force stop)
t.isAlive();        // Còn sống?
t.getName();
t.getState();
```

---

## 3. ExecutorService — Thread Pool

### 3.1 Tại sao không tạo thread trực tiếp?

```java
// ❌ BAD: Tạo thread mỗi request
@PostMapping("/authorize")
public Response authorize(Request req) {
    new Thread(() -> processAsync(req)).start();  
    // Mỗi request tạo thread mới → OOM khi load cao!
    return ok();
}
```

**Vấn đề:**
- Mỗi thread tốn ~2MB stack
- 1000 concurrent requests → 2GB RAM chỉ cho threads
- Tạo/destroy thread tốn CPU

### 3.2 ExecutorService — Pool tái sử dụng

```java
// ✅ GOOD: Reuse threads
ExecutorService executor = Executors.newFixedThreadPool(10);

for (int i = 0; i < 1000; i++) {
    executor.submit(() -> processTask());
    // 10 threads xử lý 1000 tasks, không tạo thread mới
}

executor.shutdown();  // Stop accepting new tasks
executor.awaitTermination(60, TimeUnit.SECONDS);  // Wait for completion
```

### 3.3 Các loại Executor

```java
// Fixed thread pool — luôn N threads
Executors.newFixedThreadPool(10);

// Cached pool — tăng/giảm threads theo demand (không giới hạn!)
Executors.newCachedThreadPool();  // ⚠️ Dangerous

// Single thread — 1 thread xử lý tất cả (sequential)
Executors.newSingleThreadExecutor();

// Scheduled — schedule task định kỳ
Executors.newScheduledThreadPool(5);

// ⭐ RECOMMENDED: ThreadPoolExecutor (full control)
new ThreadPoolExecutor(
    10,                           // core pool size
    20,                           // max pool size
    60L, TimeUnit.SECONDS,        // keep-alive for idle threads
    new ArrayBlockingQueue<>(100), // bounded queue (backpressure!)
    new ThreadFactoryBuilder().setNameFormat("worker-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy()  // rejection strategy
);
```

### 3.4 Sizing guideline

```
CPU-bound tasks (computation):
    pool_size = num_cores + 1
    VD: 8-core CPU → 9 threads

I/O-bound tasks (DB, HTTP calls):
    pool_size = num_cores × (1 + wait_time/compute_time)
    VD: 8-core, 90% wait → 8 × 10 = 80 threads

Mixed workload → experiment và monitor
```

---

## 4. CompletableFuture — Async programming

### 4.1 Basic usage

```java
// Async task
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    // Chạy trong thread pool (ForkJoinPool.commonPool() by default)
    Thread.sleep(1000);
    return "Result";
});

// Get result (blocking)
String result = future.get();  // Chờ đến khi xong

// Get với timeout
String result = future.get(5, TimeUnit.SECONDS);
```

### 4.2 Chaining

```java
CompletableFuture<Decision> pipeline = CompletableFuture
    .supplyAsync(() -> ingestStep.process(payment))      // Step 1
    .thenApplyAsync(ctx -> featureStep.process(ctx))     // Step 2
    .thenApplyAsync(ctx -> modelStep.process(ctx))       // Step 3
    .thenApplyAsync(ctx -> ruleStep.process(ctx))        // Step 4
    .thenApply(ctx -> decisionStep.decide(ctx));         // Step 5

Decision decision = pipeline.get();
```

### 4.3 Parallel execution

```java
// Execute parallel, combine results
CompletableFuture<Features> featuresFuture = 
    CompletableFuture.supplyAsync(() -> extractFeatures(payment));

CompletableFuture<Double> scoreFuture = 
    CompletableFuture.supplyAsync(() -> calculateScore(payment));

CompletableFuture<Decision> combined = featuresFuture
    .thenCombine(scoreFuture, (features, score) -> 
        makeDecision(features, score));
```

### 4.4 Error handling

```java
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> riskyOperation())
    .exceptionally(ex -> {
        log.error("Failed", ex);
        return "default value";
    });

// Hoặc
CompletableFuture<String> future2 = CompletableFuture
    .supplyAsync(() -> riskyOperation())
    .handle((result, ex) -> {
        if (ex != null) {
            return "fallback";
        }
        return result;
    });
```

### 4.5 Custom executor

```java
// ❌ Default uses ForkJoinPool.commonPool() — shared globally!
CompletableFuture.supplyAsync(() -> task());

// ✅ Dùng custom executor
ExecutorService customExecutor = Executors.newFixedThreadPool(10);
CompletableFuture.supplyAsync(() -> task(), customExecutor);
```

---

## 5. Thread Safety

### 5.1 Race condition

```java
// ❌ NOT thread-safe
public class Counter {
    private int count = 0;
    
    public void increment() {
        count++;  // Not atomic! (read, modify, write)
    }
}

// Result: 2 threads increment 1000 times each → expected 2000, got 1678!
```

### 5.2 Solutions

**Cách 1: synchronized**

```java
public class Counter {
    private int count = 0;
    
    public synchronized void increment() {
        count++;  // Thread-safe
    }
}
```

**Cách 2: AtomicInteger (lock-free, faster)**

```java
public class Counter {
    private final AtomicInteger count = new AtomicInteger(0);
    
    public void increment() {
        count.incrementAndGet();  // Thread-safe, no lock
    }
}
```

**Cách 3: ReentrantLock**

```java
public class Counter {
    private final ReentrantLock lock = new ReentrantLock();
    private int count = 0;
    
    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock();
        }
    }
}
```

### 5.3 Thread-safe collections

```java
// ❌ NOT thread-safe
Map<String, Payment> cache = new HashMap<>();

// ✅ Thread-safe
Map<String, Payment> cache = new ConcurrentHashMap<>();

// ❌ NOT thread-safe
List<String> list = new ArrayList<>();

// ✅ Thread-safe
List<String> list = new CopyOnWriteArrayList<>();  // For read-heavy

// ✅ Thread-safe (queue)
Queue<Task> queue = new ConcurrentLinkedQueue<>();
BlockingQueue<Task> queue = new ArrayBlockingQueue<>(100);  // With limit
```

### 5.4 Immutable objects (safest)

```java
// Record = auto-immutable
public record Payment(String id, Long amount) { }

// Traditional immutable
public final class Payment {
    private final String id;
    private final Long amount;
    
    public Payment(String id, Long amount) {
        this.id = id;
        this.amount = amount;
    }
    
    // Only getters, no setters
    public String getId() { return id; }
    public Long getAmount() { return amount; }
}
```

---

## 6. Spring Async

### 6.1 @Async annotation

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }
}

@Service
public class NotificationService {
    
    @Async("taskExecutor")
    public CompletableFuture<Void> sendNotification(Payment p) {
        // Chạy trong thread pool
        return CompletableFuture.completedFuture(null);
    }
}
```

### 6.2 ⚠️ Common Pitfalls

**Pitfall 1: Self-invocation**

```java
@Service
public class Service {
    public void outer() {
        this.asyncMethod();  // ❌ Not async!
    }
    
    @Async
    public void asyncMethod() { }
}
```

**Fix:** Gọi từ class khác, hoặc inject `self`.

**Pitfall 2: Return type**

```java
@Async
public String method() {  // ❌ Return string sẽ LUÔN null
    return "result";
}

@Async
public CompletableFuture<String> method() {  // ✅ OK
    return CompletableFuture.completedFuture("result");
}

@Async
public void method() { }  // ✅ OK (fire-and-forget)
```

**Pitfall 3: MDC không propagate**

```java
@Async
public void method() {
    log.info("...");  // traceId bị mất!
}
```

**Fix:** Dùng `TaskDecorator`:

```java
@Bean
public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(task -> {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) MDC.setContextMap(contextMap);
                task.run();
            } finally {
                MDC.clear();
            }
        };
    });
    return executor;
}
```

---

## 7. Trong dự án này

### 7.1 Variant B — Thread Pool for Model Scoring

```java
@Configuration
public class ThreadPoolConfig {
    
    @Bean(name = "modelExecutor")
    public ThreadPoolExecutor modelExecutor() {
        return new ThreadPoolExecutor(
            10, 20,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            new ThreadFactoryBuilder()
                .setNameFormat("model-%d")
                .build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}

@Component
public class AsyncModelStep implements PipelineStep {
    
    private final ThreadPoolExecutor modelExecutor;
    
    public CompletableFuture<PipelineContext> executeAsync(PipelineContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            // CPU-bound model scoring
            double score = computeRiskScore(ctx);
            ctx.setRiskScore(score);
            return ctx;
        }, modelExecutor);
    }
}
```

### 7.2 Kafka Consumer (Variant C) — Multi-threaded

```java
@KafkaListener(
    topics = "risk.ingest",
    concurrency = "6"  // 6 threads consume in parallel
)
public void consume(PaymentEvent event, Acknowledgment ack) {
    // Runs in Kafka consumer thread
    // Must be thread-safe!
}
```

### 7.3 RabbitMQ Consumer (Variant D) — 1 thread per merchant queue

```java
@Configuration
public class RabbitMQConfig {
    
    @Bean
    public SimpleRabbitListenerContainerFactory factory() {
        SimpleRabbitListenerContainerFactory factory = 
            new SimpleRabbitListenerContainerFactory();
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);  // 1 thread per queue
        return factory;
    }
}
```

---

## 8. Virtual Threads (Java 21)

### 8.1 Vấn đề với Platform Threads

```
Platform Thread (OS thread):
- 1 Platform Thread = 1 OS Thread (1:1)
- Cost: ~2MB stack
- Max ~10,000 threads before OOM
```

### 8.2 Virtual Threads

```java
// Tạo millions of virtual threads
for (int i = 0; i < 1_000_000; i++) {
    Thread.ofVirtual().start(() -> {
        // Chỉ tốn KB memory per thread
        Thread.sleep(1000);
    });
}

// ExecutorService for virtual threads
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
for (int i = 0; i < 1_000_000; i++) {
    executor.submit(() -> doWork());
}
```

**Ưu điểm:**
- Scale hơn nhiều
- Thích hợp cho I/O-bound (DB, HTTP)
- Code đồng bộ nhưng hiệu quả như async

**Nhược điểm:**
- Không tốt cho CPU-bound
- Cần `synchronized` cẩn thận (pins carrier thread)

### 8.3 Spring Boot 3.2+ config

```yaml
spring:
  threads:
    virtual:
      enabled: true  # Tomcat dùng virtual threads
```

---

## 9. Debug Thread Issues

### 9.1 Thread dump

```bash
# Find Java PID
jps

# Get thread dump
jstack <PID> > threads.txt

# Hoặc dùng jcmd
jcmd <PID> Thread.print > threads.txt
```

### 9.2 Đọc thread dump

```
"http-nio-8080-exec-1" #37 daemon prio=5 os_prio=0 tid=0x00007f...
   java.lang.Thread.State: BLOCKED (on object monitor)
    at com.fraud.api.service.PaymentService.method(PaymentService.java:42)
    - waiting to lock <0x00000000d1234567> (a java.lang.Object)
    at ...
```

**Thông tin quan trọng:**
- Thread name: `http-nio-8080-exec-1`
- State: `BLOCKED`, `WAITING`, `RUNNABLE`
- Stack trace: ở đâu
- Lock info: đang chờ lock nào

### 9.3 Deadlock detection

```bash
jstack <PID> | findstr -i deadlock
```

```
Found one Java-level deadlock:
=============================
"Thread-A": waiting to lock Monitor@0x... (Object)
"Thread-B": waiting to lock Monitor@0x... (Object)
```

### 9.4 Common issues

**Issue 1: Deadlock**

```java
// ❌ Deadlock pattern
synchronized(lockA) {
    synchronized(lockB) { }  // Thread 1
}

synchronized(lockB) {
    synchronized(lockA) { }  // Thread 2 → DEADLOCK!
}

// ✅ Fix: Always acquire locks in same order
synchronized(lockA) {
    synchronized(lockB) { }  // Both threads
}
```

**Issue 2: Thread pool exhaustion**

```
WARN: ThreadPoolExecutor: Task rejected
```

**Fix:** Tăng `maxPoolSize`, `queueCapacity`, hoặc dùng `CallerRunsPolicy`.

**Issue 3: Leaked threads**

```bash
# Check thread count
jcmd <PID> Thread.print | grep "^\"" | wc -l

# Nếu tăng dần → có thread leak
# Check xem có quên shutdown executor không
```

---

## 10. Kiểm tra hiểu bài

1. Threads trong cùng process share gì?
2. Khi nào dùng `CachedThreadPool` vs `FixedThreadPool`?
3. Race condition xảy ra khi nào?
4. `@Async` self-invocation có work không?
5. Virtual thread khác gì platform thread?

### Đáp án

1. Heap, static fields, code segment. Stack riêng cho mỗi thread
2. Cached: không giới hạn, chỉ dùng khi tasks ngắn và predictable load. Fixed: có giới hạn, an toàn hơn
3. 2+ threads access shared mutable state, không có synchronization
4. KHÔNG (Spring proxy bypass khi gọi `this.method()`)
5. Virtual: lightweight (KB), managed by JVM, scale millions. Platform: 1:1 với OS thread, tốn 2MB

---

## 📚 Tiếp theo

→ [`08-debugging-guide.md`](./08-debugging-guide.md) — Debug guide
