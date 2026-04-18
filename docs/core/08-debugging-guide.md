# 08. Debugging Guide — Debug như một chuyên gia

## 🎯 Mục tiêu

- Biết cách đọc stack trace để tìm bug
- Dùng IDE debugger thành thạo
- Debug remote application
- Phân tích memory leak, thread issues

---

## 1. Triết lý debug

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      DEBUG MINDSET                                     │
└─────────────────────────────────────────────────────────────────────────┘

    Level 1: Log-based debugging
    ├── println("here 1")
    ├── println("here 2")
    └── Guess what went wrong
    
    Level 2: Proper logging
    ├── Structured logs with traceId
    ├── Log at key decision points
    └── Correlate logs across services
    
    Level 3: Debugger
    ├── Set breakpoints
    ├── Step through code
    └── Inspect variables
    
    Level 4: Production debugging
    ├── Thread dumps
    ├── Heap dumps
    ├── Profilers
    └── Distributed tracing
    
    Bạn cần: Level 2-3 cho dev, Level 4 cho prod
```

---

## 2. Đọc Stack Trace

### 2.1 Cấu trúc stack trace

```
Exception in thread "main" java.lang.NullPointerException: Cannot invoke 
    "com.fraud.api.domain.Payment.getAmount()" because "payment" is null
    
    at com.fraud.api.service.PaymentService.authorize(PaymentService.java:42)
    at com.fraud.api.controller.PaymentController.authorize(PaymentController.java:28)
    at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
    ...
    at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1071)
    ...
```

### 2.2 Đọc từ trên xuống hay dưới lên?

**Đọc TỪ TRÊN XUỐNG** (từ exception → caller):

```
1. Exception type:     NullPointerException
2. Exception message:  Cannot invoke getAmount() because payment is null
3. Thread name:        main
4. Where thrown:       PaymentService.authorize line 42  ← ROOT CAUSE
5. Called by:          PaymentController.authorize line 28
6. ...deep stack...
```

**Nguyên tắc:**
- **Root cause** ở dòng đầu tiên của **your code**
- Skip framework code (Spring, Tomcat, JVM internals)
- Chú ý `Caused by:` — nested exception

### 2.3 Ví dụ thực tế

```
ERROR 12345 --- [http-nio-8080-exec-1] c.f.a.s.PaymentService : Error processing payment

org.springframework.dao.DataIntegrityViolationException: could not execute statement
    at org.springframework.orm.jpa.EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible
    at ...
    
Caused by: org.hibernate.exception.ConstraintViolationException: could not execute statement
    at org.hibernate.dialect.Dialect$1.convert
    at ...
    
Caused by: org.postgresql.util.PSQLException: ERROR: duplicate key value violates unique constraint 
    "uk_merchant_idempotency"
    Detail: Key (merchant_id, idempotency_key)=(m-001, idem-123) already exists.
    at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse
    at ...
```

**Phân tích:**
1. Spring báo `DataIntegrityViolationException` (high-level)
2. Hibernate báo `ConstraintViolationException` (middle)
3. **PostgreSQL báo:** `duplicate key` — **ROOT CAUSE**
4. Fix: Check idempotency trước khi insert

---

## 3. IDE Debugger

### 3.1 Setup IntelliJ / VSCode

**IntelliJ:**
1. Click bên trái số dòng → tạo breakpoint (đỏ)
2. Right-click `main()` → "Debug Application"
3. App pause tại breakpoint

**VSCode:**
1. Install "Extension Pack for Java"
2. Click F5 → chọn launch config
3. Set breakpoints với F9

### 3.2 Debug actions

| Action | Shortcut (IntelliJ) | Mục đích |
|--------|---------------------|----------|
| Resume | F9 | Chạy tiếp đến breakpoint tiếp |
| Step Over | F8 | Chạy dòng hiện tại, không vào method |
| Step Into | F7 | Vào method đang gọi |
| Step Out | Shift+F8 | Chạy hết method hiện tại |
| Evaluate Expression | Alt+F8 | Test biểu thức |
| View Variables | Trong Debug panel | Xem giá trị biến |

### 3.3 Advanced breakpoints

**Conditional breakpoint:**
```java
// Pause chỉ khi: payment.amount > 1000000
if (payment.getAmount() > 1000000) {
    // Set breakpoint here with condition
}
```

Right-click breakpoint → "Condition" → `payment.getAmount() > 1000000`

**Exception breakpoint:**
- Pause mỗi khi `NullPointerException` được throw
- Run → View Breakpoints → Add Exception Breakpoint

**Field breakpoint:**
- Pause khi field bị read/write
- Click breakpoint trên field declaration

---

## 4. Remote Debugging

### 4.1 Chạy app với debug port

```bash
# Standard JVM debug
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar app.jar

# Spring Boot
./gradlew bootRun --debug-jvm
# → Mở port 5005 mặc định

# Tự config port
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:9009 \
     -jar app.jar
```

**Param giải thích:**
- `server=y` → app là debug server
- `suspend=n` → không dừng lúc start (y = wait for debugger)
- `address=*:5005` → listen port 5005

### 4.2 Connect IDE

**IntelliJ:**
1. Run → Edit Configurations → Add Remote JVM Debug
2. Host: localhost, Port: 5005
3. Click Debug

**VSCode:**
```json
// .vscode/launch.json
{
    "type": "java",
    "name": "Debug (Attach)",
    "request": "attach",
    "hostName": "localhost",
    "port": 5005
}
```

### 4.3 Debug Docker container

```dockerfile
EXPOSE 5005

CMD java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
         -jar app.jar
```

```bash
docker run -p 8080:8080 -p 5005:5005 fraud-api
```

---

## 5. Logging — Debug không cần debugger

### 5.1 Logback levels

```java
log.trace("Very detailed info");   // Everything
log.debug("Debug info");           // Detailed debug
log.info("Important events");      // Normal operation
log.warn("Something wrong");       // Potential issue
log.error("Error occurred", e);    // Error with stack trace
```

### 5.2 Config levels

```yaml
logging:
  level:
    root: INFO
    com.fraud: DEBUG           # Dự án của mình: DEBUG
    org.hibernate.SQL: DEBUG   # Show SQL
    org.springframework.web: DEBUG  # Show request handling
```

### 5.3 Log structured

```java
// ❌ BAD: Unstructured
log.info("Processing payment " + paymentId + " for " + merchantId);

// ✅ GOOD: Structured with context
log.info("Processing payment", 
    kv("paymentId", paymentId),
    kv("merchantId", merchantId),
    kv("amount", amount));

// ✅ BEST: MDC context
MDC.put("paymentId", paymentId);
MDC.put("merchantId", merchantId);
try {
    log.info("Processing payment");
    log.info("Step 1 completed");
} finally {
    MDC.clear();
}
```

### 5.4 Log format với traceId

```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{36} - %msg%n
            </pattern>
        </encoder>
    </appender>
</configuration>
```

Output:
```
10:30:15.123 [http-nio-8080-exec-1] [abc-123-def] INFO  c.f.a.s.PaymentService - Processing payment
10:30:15.145 [http-nio-8080-exec-1] [abc-123-def] INFO  c.f.a.s.PaymentService - Step 1 completed
```

---

## 6. Debug Techniques — Cookbook

### 6.1 Bug: Response không đúng

**Debug flow:**

```
1. Check input
   └── Log request body ở controller
   
2. Check business logic
   └── Log variables trong service
   
3. Check DB query
   └── Enable SQL logging
   
4. Check response serialization
   └── Log response object before return
```

**Code:**

```java
@PostMapping("/authorize")
public AuthorizeResponse authorize(@RequestBody AuthorizeRequest req) {
    log.debug("Request: {}", req);  // ← Step 1
    
    AuthorizeResponse resp = paymentService.authorize(req);
    
    log.debug("Response: {}", resp);  // ← Step 4
    return resp;
}
```

### 6.2 Bug: Database không update

**Common causes:**

```java
// Cause 1: Không save
@Service
public class PaymentService {
    public void updateStatus(String id) {
        Payment p = repo.findById(id).get();
        p.setStatus("DONE");
        // ❌ Quên repo.save(p)!
    }
}

// Fix: Gọi save hoặc dùng @Transactional (dirty checking)
@Transactional
public void updateStatus(String id) {
    Payment p = repo.findById(id).get();
    p.setStatus("DONE");
    // ✓ Auto save khi transaction commit
}

// Cause 2: Transaction rollback do exception
@Transactional
public void method() {
    repo.save(p);
    throw new RuntimeException();  // ← ROLLBACK!
}

// Cause 3: @Transactional không hoạt động (self-invocation)
public void outer() {
    this.inner();  // ❌ @Transactional bypass
}
@Transactional
public void inner() { }
```

### 6.3 Bug: Slow API

**Step 1: Đo thời gian**

```java
long start = System.currentTimeMillis();
service.authorize(req);
long elapsed = System.currentTimeMillis() - start;
log.info("authorize took {}ms", elapsed);
```

**Step 2: Identify bottleneck**

```java
StopWatch sw = new StopWatch();

sw.start("ingestStep");
ingestStep.execute(ctx);
sw.stop();

sw.start("featureStep");
featureStep.execute(ctx);
sw.stop();

log.info(sw.prettyPrint());
// Output:
// StopWatch '': running time = 450ms
// ns         %     Task name
// 50000000   11%   ingestStep
// 400000000  88%   featureStep  ← Bottleneck!
```

**Step 3: Profile method**

```bash
# Async Profiler
./profiler.sh -d 30 -f profile.html <PID>

# Open profile.html → flamegraph
```

### 6.4 Bug: OutOfMemoryError

```bash
# Heap dump khi OOM
java -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/tmp/heap.hprof \
     -jar app.jar

# Manual heap dump
jmap -dump:live,format=b,file=heap.hprof <PID>
```

**Analyze:**
- Eclipse MAT (Memory Analyzer Tool)
- IntelliJ Profiler
- VisualVM

**Common causes:**
- Unbounded collections (List growing forever)
- Memory leak (references không được release)
- Connection leak

---

## 7. Debug Production

### 7.1 Thread dump

```bash
# Find PID
jps

# Thread dump
jstack <PID> > threads.txt

# Hoặc
jcmd <PID> Thread.print > threads.txt

# Multiple dumps (để so sánh)
for i in 1 2 3; do
    jstack <PID> > threads_$i.txt
    sleep 5
done
```

**Analyze:**
- Tìm threads ở state `BLOCKED`, `WAITING`
- Check stack trace — đang làm gì?
- So sánh multiple dumps — thread nào stuck?

### 7.2 Heap dump

```bash
# Dump heap (không restart)
jmap -dump:live,format=b,file=/tmp/heap.hprof <PID>

# Xem memory usage
jmap -histo <PID> | head -20

# Result:
# num     #instances         #bytes  class name
# ----------------------------------------------
#    1:       1234567      123456789  [B (byte array)
#    2:       234567       23456789  java.lang.String
#    3:       123456       12345678  java.util.HashMap
```

### 7.3 GC logs

```bash
# Enable GC logging
java -Xlog:gc*:file=gc.log:time,level,tags \
     -jar app.jar

# Analyze với GCEasy.io hoặc GCViewer
```

### 7.4 JMX monitoring

```bash
# Enable JMX
java -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9010 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar app.jar

# Connect với JConsole, VisualVM, hoặc JMC
```

---

## 8. Debug trong dự án này

### 8.1 Variant A: Sync pipeline

```yaml
# application.yml
logging:
  level:
    com.fraud: DEBUG
    com.fraud.api.pipeline: TRACE  # Xem chi tiết pipeline
```

Log sẽ show:
```
TRACE IngestStep: Executing for payment p-123
DEBUG IngestStep: Validation passed
TRACE FeatureStep: Executing for payment p-123
DEBUG FeatureStep: Features extracted: {...}
...
```

### 8.2 Variant B: Thread pool

```yaml
logging:
  level:
    com.fraud.api.pipeline.steps.AsyncModelStep: DEBUG

# Monitor thread pool
management:
  endpoints:
    web:
      exposure:
        include: metrics
```

```bash
# Check thread pool stats
curl http://localhost:8080/actuator/metrics/executor.active
curl http://localhost:8080/actuator/metrics/executor.queued
```

### 8.3 Variant C: Kafka

```yaml
logging:
  level:
    org.apache.kafka: INFO
    com.fraud.api.kafka: DEBUG
    org.springframework.kafka: DEBUG
```

```bash
# Kafka lag
docker exec fraud-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group fraud-group \
    --describe
```

### 8.4 Variant D: RabbitMQ

```yaml
logging:
  level:
    com.fraud.api.rabbitmq: DEBUG
    org.springframework.amqp: DEBUG
```

```bash
# RabbitMQ queue status
curl -u fraud:fraud http://localhost:15672/api/queues/%2F/merchant.m-001
```

---

## 9. Debug Checklist

### 9.1 App không start

```
☐ Check JAVA_HOME (java -version)
☐ Check port 8080 không bị dùng (netstat -ano | findstr 8080)
☐ Check application.yml syntax
☐ Check database running (docker ps)
☐ Check logs cho "Error starting ApplicationContext"
☐ Bật --debug để xem auto-config
```

### 9.2 Request trả 500

```
☐ Check logs cho stack trace
☐ Identify root cause exception
☐ Check if NullPointerException → fix null check
☐ Check if constraint violation → fix data
☐ Check if transaction rollback → fix logic
```

### 9.3 Request chậm

```
☐ Enable SQL logging → check N+1
☐ Check DB indexes (EXPLAIN ANALYZE)
☐ Profile method bằng StopWatch
☐ Check thread pool exhaustion
☐ Check external API calls (webhook)
☐ Check connection pool size
```

### 9.4 Memory leak

```
☐ Monitor heap size over time (jstat)
☐ Take heap dump (jmap)
☐ Analyze với MAT → find retaining objects
☐ Check unbounded collections
☐ Check static fields
☐ Check executor shutdown
```

---

## 10. Tools Cheatsheet

| Tool | Mục đích | Command |
|------|----------|---------|
| `jps` | List Java processes | `jps -v` |
| `jstack` | Thread dump | `jstack <PID>` |
| `jmap` | Heap info | `jmap -histo <PID>` |
| `jstat` | GC stats | `jstat -gc <PID> 1000` |
| `jcmd` | Swiss army knife | `jcmd <PID> help` |
| `jconsole` | GUI monitoring | `jconsole <PID>` |
| `jvisualvm` | Profiler GUI | `jvisualvm` |
| `async-profiler` | CPU profiler | `./profiler.sh -d 30 <PID>` |
| `Eclipse MAT` | Heap analysis | GUI |

---

## 11. Kiểm tra hiểu bài

1. Khi đọc stack trace, tìm root cause ở đâu?
2. Conditional breakpoint là gì?
3. Làm sao debug Docker container?
4. `jstack` dùng để làm gì?
5. Khi API chậm, các bước debug là gì?

### Đáp án

1. Dòng đầu tiên trong **your code** (skip framework), chú ý `Caused by:` sâu nhất
2. Breakpoint chỉ trigger khi điều kiện true
3. Expose port debug (5005) + attach IDE
4. Thread dump — xem threads đang làm gì
5. Enable SQL logging → profile method → check thread pool → check connections

---

## 📚 Tiếp theo

→ [`09-common-errors.md`](./09-common-errors.md) — Lỗi thường gặp và cách fix
