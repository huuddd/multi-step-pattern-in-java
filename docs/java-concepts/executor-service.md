# Java Concept: ExecutorService & ThreadPoolExecutor

## 1. Tổng quan

**ExecutorService** là interface trong `java.util.concurrent` để quản lý và thực thi tasks bất đồng bộ. **ThreadPoolExecutor** là implementation phổ biến nhất.

## 2. Tại sao cần Thread Pool?

### Vấn đề với Thread-per-Request

```java
// ❌ Anti-pattern: tạo thread mới cho mỗi request
new Thread(() -> processRequest(request)).start();
```

**Vấn đề:**
- **Chi phí tạo thread cao** — mỗi thread tốn ~1MB stack memory
- **Context switching** — nhiều thread = nhiều overhead
- **Không kiểm soát** — traffic spike → OOM

### Giải pháp: Thread Pool

```java
// ✅ Reuse threads từ pool
ExecutorService pool = Executors.newFixedThreadPool(10);
pool.submit(() -> processRequest(request));
```

**Lợi ích:**
- **Reuse threads** — không tạo mới liên tục
- **Bounded** — giới hạn số thread/queue
- **Backpressure** — reject khi quá tải

## 3. ThreadPoolExecutor — Chi tiết

### 3.1 Constructor

```java
public ThreadPoolExecutor(
    int corePoolSize,           // Số thread tối thiểu
    int maximumPoolSize,        // Số thread tối đa
    long keepAliveTime,         // Thời gian giữ thread thừa
    TimeUnit unit,              // Đơn vị thời gian
    BlockingQueue<Runnable> workQueue,  // Queue chứa tasks
    ThreadFactory threadFactory,        // Factory tạo thread
    RejectedExecutionHandler handler    // Xử lý khi reject
)
```

### 3.2 Luồng xử lý Task

```
                    ┌─────────────────────────────────────────────────────┐
                    │              TASK SUBMISSION FLOW                   │
                    └─────────────────────────────────────────────────────┘

    submit(task)
         │
         ▼
    ┌─────────────────┐
    │ corePoolSize    │──── Có thread rảnh? ────▶ YES ──▶ Chạy ngay
    │ đã đủ chưa?     │                                      │
    └────────┬────────┘                                      │
             │ NO                                            │
             ▼                                               │
    ┌─────────────────┐                                      │
    │ Tạo core thread │──────────────────────────────────────┘
    │ mới             │
    └────────┬────────┘
             │ Đã đủ corePoolSize
             ▼
    ┌─────────────────┐
    │ Queue còn chỗ?  │──── YES ──▶ Đưa vào queue
    └────────┬────────┘
             │ NO (queue đầy)
             ▼
    ┌─────────────────┐
    │ < maxPoolSize?  │──── YES ──▶ Tạo thread mới (non-core)
    └────────┬────────┘
             │ NO (đã max)
             ▼
    ┌─────────────────┐
    │ RejectedHandler │──── Reject task
    └─────────────────┘
```

### 3.3 Các loại Queue

| Queue Type | Đặc điểm | Use case |
|------------|----------|----------|
| `ArrayBlockingQueue(n)` | Bounded, FIFO | **Khuyến nghị** — có backpressure |
| `LinkedBlockingQueue()` | Unbounded | ⚠️ Có thể OOM |
| `LinkedBlockingQueue(n)` | Bounded | Tương tự ArrayBlockingQueue |
| `SynchronousQueue` | Không buffer | Handoff trực tiếp |
| `PriorityBlockingQueue` | Priority ordering | Task có độ ưu tiên |

### 3.4 RejectedExecutionHandler

Khi pool đầy và queue đầy, handler quyết định làm gì:

```java
// 1. AbortPolicy (default) — throw RejectedExecutionException
new ThreadPoolExecutor.AbortPolicy()

// 2. CallerRunsPolicy — chạy trong thread gọi (backpressure tự nhiên)
new ThreadPoolExecutor.CallerRunsPolicy()

// 3. DiscardPolicy — bỏ task im lặng
new ThreadPoolExecutor.DiscardPolicy()

// 4. DiscardOldestPolicy — bỏ task cũ nhất trong queue
new ThreadPoolExecutor.DiscardOldestPolicy()

// 5. Custom handler
new RejectedExecutionHandler() {
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        // Log, metrics, throw custom exception...
        throw new ServiceOverloadedException("Thread pool exhausted");
    }
}
```

## 4. Sizing Guidelines

### 4.1 CPU-bound Tasks

```java
// Số thread = số CPU cores
int corePoolSize = Runtime.getRuntime().availableProcessors();
```

**Lý do:** CPU-bound tasks sử dụng 100% CPU. Nhiều thread hơn số core = context switching overhead.

### 4.2 I/O-bound Tasks

```java
// Số thread = cores * (1 + wait_time / compute_time)
// Ví dụ: 80% thời gian chờ I/O, 20% compute
int corePoolSize = cores * (1 + 0.8 / 0.2) = cores * 5;
```

**Lý do:** Thread chờ I/O không dùng CPU, có thể chạy nhiều thread hơn.

### 4.3 Mixed Workload

```java
// Tách thành 2 pools
ExecutorService cpuPool = new ThreadPoolExecutor(cores, cores, ...);
ExecutorService ioPool = new ThreadPoolExecutor(cores * 5, cores * 10, ...);
```

## 5. Best Practices

### 5.1 Luôn dùng Bounded Queue

```java
// ❌ Unbounded — có thể OOM
new LinkedBlockingQueue<>()

// ✅ Bounded — có backpressure
new ArrayBlockingQueue<>(1000)
```

### 5.2 Đặt tên Thread

```java
ThreadFactory namedFactory = new ThreadFactory() {
    private final AtomicInteger counter = new AtomicInteger(0);
    
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName("model-scorer-" + counter.incrementAndGet());
        return t;
    }
};
```

**Lợi ích:** Dễ debug, dễ đọc thread dump.

### 5.3 Graceful Shutdown

```java
// 1. Ngừng nhận task mới
executor.shutdown();

// 2. Chờ tasks đang chạy hoàn thành
if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
    // 3. Force shutdown nếu quá lâu
    executor.shutdownNow();
}
```

### 5.4 Monitor Metrics

```java
ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;

// Số task đã submit
pool.getTaskCount();

// Số task đã hoàn thành
pool.getCompletedTaskCount();

// Số thread đang active
pool.getActiveCount();

// Số task trong queue
pool.getQueue().size();
```

## 6. Trong Fraud Detection Gateway

### 6.1 Model Scoring Pool

```java
@Configuration
public class ThreadPoolConfig {
    
    @Bean
    public ThreadPoolExecutor modelScoringPool() {
        int cores = Runtime.getRuntime().availableProcessors();
        
        return new ThreadPoolExecutor(
            cores,              // corePoolSize = CPU cores
            cores * 2,          // maxPoolSize = 2x cores
            60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500),  // bounded queue
            namedThreadFactory("model-scorer"),
            new BackpressureRejectionHandler()  // custom handler
        );
    }
}
```

### 6.2 Tại sao Model Scoring?

```
Pipeline: Ingest → Feature → MODEL → Rule → Decision
                              ↑
                         CPU-bound
                    (ML inference / heuristics)
```

- **Ingest, Feature, Rule, Decision** — I/O-bound (DB queries)
- **Model** — CPU-bound (tính toán risk score)

Tách Model ra pool riêng để:
1. Không block request thread
2. Kiểm soát concurrency
3. Có backpressure khi quá tải

## 7. Common Pitfalls

### 7.1 Deadlock với CallerRunsPolicy

```java
// ❌ Có thể deadlock nếu task submit task khác
pool.submit(() -> {
    // Task A submit Task B
    pool.submit(() -> doSomething());  // Nếu pool đầy, CallerRunsPolicy
                                        // chạy B trong thread của A
                                        // A chờ B, B chờ A → deadlock
});
```

### 7.2 Quên Shutdown

```java
// ❌ Thread pool không shutdown → JVM không exit
ExecutorService pool = Executors.newFixedThreadPool(10);
// ... use pool ...
// Quên pool.shutdown() → JVM hangs

// ✅ Dùng try-with-resources (Java 19+) hoặc @PreDestroy
@PreDestroy
public void cleanup() {
    pool.shutdown();
}
```

### 7.3 Exception Swallowing

```java
// ❌ Exception bị nuốt
pool.submit(() -> {
    throw new RuntimeException("Error!");  // Không thấy exception
});

// ✅ Wrap trong try-catch hoặc dùng Future.get()
Future<?> future = pool.submit(() -> {
    throw new RuntimeException("Error!");
});
try {
    future.get();  // Exception được throw ở đây
} catch (ExecutionException e) {
    log.error("Task failed", e.getCause());
}
```

## 8. Tham khảo

- [Java Concurrency in Practice](https://jcip.net/) — Chapter 8: Thread Pools
- [ThreadPoolExecutor Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
- [Baeldung: Thread Pool](https://www.baeldung.com/thread-pool-java-and-guava)
