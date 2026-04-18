# Java Concept: CompletableFuture

## 1. Tổng quan

**CompletableFuture** (Java 8+) là class cho phép:
- Chạy tasks **bất đồng bộ** (async)
- **Chain** nhiều operations (thenApply, thenCompose)
- **Combine** nhiều futures (allOf, anyOf)
- Xử lý **exceptions** (exceptionally, handle)

## 2. Tại sao cần CompletableFuture?

### Vấn đề với Future cũ

```java
// ❌ Future cũ: blocking, không chain được
Future<Integer> future = executor.submit(() -> compute());
Integer result = future.get();  // BLOCKING!
```

### Giải pháp: CompletableFuture

```java
// ✅ CompletableFuture: non-blocking, chainable
CompletableFuture.supplyAsync(() -> compute())
    .thenApply(result -> transform(result))
    .thenAccept(transformed -> save(transformed))
    .exceptionally(ex -> handleError(ex));
```

## 3. Tạo CompletableFuture

### 3.1 supplyAsync — có return value

```java
// Chạy trong ForkJoinPool.commonPool() (default)
CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(() -> {
    return "Hello";
});

// Chạy trong custom executor
CompletableFuture<String> cf2 = CompletableFuture.supplyAsync(
    () -> "Hello",
    customExecutor
);
```

### 3.2 runAsync — không có return value

```java
CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
    doSomething();
});
```

### 3.3 completedFuture — đã có kết quả

```java
// Tạo future đã complete (useful for testing, caching)
CompletableFuture<String> cf = CompletableFuture.completedFuture("cached");
```

## 4. Chaining Operations

### 4.1 thenApply — transform result (sync)

```java
CompletableFuture<Integer> lengthFuture = 
    CompletableFuture.supplyAsync(() -> "Hello")
        .thenApply(s -> s.length());  // 5
```

### 4.2 thenApplyAsync — transform result (async)

```java
CompletableFuture<Integer> lengthFuture = 
    CompletableFuture.supplyAsync(() -> "Hello")
        .thenApplyAsync(s -> s.length(), anotherExecutor);
```

### 4.3 thenCompose — chain futures (flatMap)

```java
// Khi transformation trả về CompletableFuture
CompletableFuture<User> userFuture = 
    CompletableFuture.supplyAsync(() -> getUserId())
        .thenCompose(id -> fetchUserAsync(id));  // fetchUserAsync returns CF<User>
```

### 4.4 thenAccept — consume result (no return)

```java
CompletableFuture.supplyAsync(() -> "Hello")
    .thenAccept(s -> System.out.println(s));
```

### 4.5 thenRun — run after completion (ignore result)

```java
CompletableFuture.supplyAsync(() -> "Hello")
    .thenRun(() -> System.out.println("Done!"));
```

## 5. Combining Futures

### 5.1 thenCombine — combine 2 futures

```java
CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(() -> "Hello");
CompletableFuture<String> cf2 = CompletableFuture.supplyAsync(() -> "World");

CompletableFuture<String> combined = cf1.thenCombine(cf2, 
    (s1, s2) -> s1 + " " + s2);  // "Hello World"
```

### 5.2 allOf — wait for all

```java
CompletableFuture<Void> all = CompletableFuture.allOf(cf1, cf2, cf3);
all.join();  // Wait for all to complete
```

### 5.3 anyOf — wait for first

```java
CompletableFuture<Object> any = CompletableFuture.anyOf(cf1, cf2, cf3);
Object firstResult = any.join();  // First to complete
```

## 6. Exception Handling

### 6.1 exceptionally — recover from exception

```java
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
    if (error) throw new RuntimeException("Error!");
    return "Success";
}).exceptionally(ex -> {
    log.error("Failed", ex);
    return "Default";  // Recovery value
});
```

### 6.2 handle — handle both success and failure

```java
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> compute())
    .handle((result, ex) -> {
        if (ex != null) {
            return "Error: " + ex.getMessage();
        }
        return "Success: " + result;
    });
```

### 6.3 whenComplete — side effect on completion

```java
CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> compute())
    .whenComplete((result, ex) -> {
        if (ex != null) {
            log.error("Failed", ex);
        } else {
            log.info("Completed: {}", result);
        }
    });
// Note: whenComplete doesn't change the result
```

## 7. Getting Results

### 7.1 get() — blocking, throws checked exceptions

```java
try {
    String result = cf.get();  // Blocks indefinitely
} catch (InterruptedException | ExecutionException e) {
    // Handle
}
```

### 7.2 get(timeout) — blocking with timeout

```java
try {
    String result = cf.get(5, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    // Timeout!
}
```

### 7.3 join() — blocking, throws unchecked exceptions

```java
String result = cf.join();  // Throws CompletionException (unchecked)
```

### 7.4 getNow(default) — non-blocking

```java
String result = cf.getNow("default");  // Returns default if not complete
```

## 8. Trong Fraud Detection Gateway

### 8.1 Async Model Scoring

```java
@Service
public class AsyncModelStep {
    
    private final ThreadPoolExecutor modelScoringExecutor;
    
    public CompletableFuture<BigDecimal> scoreAsync(PipelineContext context) {
        return CompletableFuture.supplyAsync(
            () -> calculateRiskScore(context),
            modelScoringExecutor
        ).orTimeout(5, TimeUnit.SECONDS);  // Timeout protection
    }
    
    private BigDecimal calculateRiskScore(PipelineContext context) {
        // CPU-bound computation
        return BigDecimal.valueOf(computeScore(context.getFeatures()));
    }
}
```

### 8.2 Pipeline với Async Step

```java
public PipelineContext execute(Payment payment) {
    PipelineContext context = PipelineContext.builder()
            .payment(payment)
            .build();
    
    // Sync steps
    context = ingestStep.execute(context);
    context = featureStep.execute(context);
    
    // Async model scoring
    CompletableFuture<BigDecimal> scoreFuture = asyncModelStep.scoreAsync(context);
    
    try {
        // Wait for result with timeout
        BigDecimal score = scoreFuture.get(5, TimeUnit.SECONDS);
        context.setRiskScore(score);
    } catch (TimeoutException e) {
        // Handle timeout - use default score or fail
        context.setRiskScore(BigDecimal.valueOf(0.5));
    }
    
    // Continue with sync steps
    context = ruleStep.execute(context);
    context = decisionStep.execute(context);
    
    return context;
}
```

## 9. Best Practices

### 9.1 Luôn specify Executor

```java
// ❌ Dùng common pool (shared, có thể bị block)
CompletableFuture.supplyAsync(() -> cpuBoundTask());

// ✅ Dùng dedicated executor
CompletableFuture.supplyAsync(() -> cpuBoundTask(), dedicatedExecutor);
```

### 9.2 Luôn có Timeout

```java
// ❌ Không có timeout - có thể hang forever
cf.get();

// ✅ Có timeout
cf.get(5, TimeUnit.SECONDS);

// ✅ Hoặc dùng orTimeout (Java 9+)
cf.orTimeout(5, TimeUnit.SECONDS);
```

### 9.3 Handle Exceptions

```java
// ❌ Exception bị nuốt
CompletableFuture.supplyAsync(() -> riskyOperation());

// ✅ Handle exception
CompletableFuture.supplyAsync(() -> riskyOperation())
    .exceptionally(ex -> {
        log.error("Failed", ex);
        return defaultValue;
    });
```

### 9.4 Avoid Blocking trong Async Chain

```java
// ❌ Blocking trong async chain
cf.thenApply(result -> {
    return anotherCf.get();  // BLOCKING!
});

// ✅ Dùng thenCompose
cf.thenCompose(result -> anotherCf);
```

## 10. Common Pitfalls

### 10.1 ForkJoinPool Starvation

```java
// ❌ Blocking I/O trong common pool
CompletableFuture.supplyAsync(() -> {
    return httpClient.get(url);  // Blocking I/O!
});
// Common pool chỉ có ít threads, blocking = starvation

// ✅ Dùng I/O executor
CompletableFuture.supplyAsync(() -> httpClient.get(url), ioExecutor);
```

### 10.2 Exception trong thenApply

```java
// Exception trong thenApply sẽ wrap trong CompletionException
cf.thenApply(result -> {
    throw new RuntimeException("Error!");
}).exceptionally(ex -> {
    // ex là CompletionException, cause là RuntimeException
    Throwable cause = ex.getCause();
    return defaultValue;
});
```

## 11. Tham khảo

- [Java CompletableFuture Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [Baeldung: CompletableFuture](https://www.baeldung.com/java-completablefuture)
- [Modern Java in Action](https://www.manning.com/books/modern-java-in-action) — Chapter 16
