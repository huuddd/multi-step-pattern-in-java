# 09. Common Errors — Lỗi thường gặp và cách Fix

## 🎯 Mục tiêu

- Nhận diện nhanh các lỗi phổ biến
- Biết root cause và cách fix
- Tích lũy kinh nghiệm debug

---

## 1. Compilation Errors

### 1.1 `cannot find symbol`

```java
// ❌ Lỗi
public class PaymentService {
    public void process() {
        Payment p = new Payment();
        p.setStatus("DONE");  // cannot find symbol: method setStatus
    }
}
```

**Nguyên nhân:**
- Method/field chưa tồn tại
- Import sai
- Typo

**Fix:**
```java
// Thêm setter
public class Payment {
    private String status;
    public void setStatus(String status) { this.status = status; }
}
```

### 1.2 `package does not exist`

```java
// ❌ Lỗi
import org.springframework.kafka.core.KafkaTemplate;  
// package org.springframework.kafka.core does not exist
```

**Fix:**
```kotlin
// build.gradle.kts — thêm dependency
dependencies {
    implementation("org.springframework.kafka:spring-kafka")
}
```

Refresh Gradle: `./gradlew build --refresh-dependencies`

### 1.3 `incompatible types`

```java
String s = 123;  // incompatible types: int cannot be converted to String
```

**Fix:**
```java
String s = String.valueOf(123);  // Or Integer.toString(123)
```

---

## 2. Runtime Errors

### 2.1 NullPointerException

```java
// ❌ Nguyên nhân thường gặp
Payment p = paymentRepo.findById(id);  // Có thể return null
String status = p.getStatus();  // NPE!
```

**Fix:**

```java
// Cách 1: Null check
Payment p = paymentRepo.findById(id);
if (p != null) {
    String status = p.getStatus();
}

// Cách 2: Optional (Spring Data JPA)
Optional<Payment> opt = paymentRepo.findById(id);
opt.ifPresent(p -> {
    String status = p.getStatus();
});

// Cách 3: orElse / orElseThrow
Payment p = paymentRepo.findById(id)
    .orElseThrow(() -> new PaymentNotFoundException(id));

// Cách 4: Elvis-like với Optional
String status = paymentRepo.findById(id)
    .map(Payment::getStatus)
    .orElse("UNKNOWN");
```

### 2.2 ClassCastException

```java
Object obj = "hello";
Integer i = (Integer) obj;  // ClassCastException
```

**Fix:**

```java
// Check trước khi cast
if (obj instanceof Integer) {
    Integer i = (Integer) obj;
}

// Pattern matching (Java 16+)
if (obj instanceof Integer i) {
    System.out.println(i);
}
```

### 2.3 IndexOutOfBoundsException

```java
List<String> list = List.of("a", "b");
String s = list.get(5);  // IndexOutOfBoundsException
```

**Fix:**
```java
if (list.size() > 5) {
    String s = list.get(5);
}
```

### 2.4 ConcurrentModificationException

```java
// ❌ Modify list while iterating
List<Payment> payments = new ArrayList<>(...);
for (Payment p : payments) {
    if (p.getState() == PaymentState.EXPIRED) {
        payments.remove(p);  // ConcurrentModificationException!
    }
}
```

**Fix:**

```java
// Cách 1: Iterator.remove()
Iterator<Payment> it = payments.iterator();
while (it.hasNext()) {
    Payment p = it.next();
    if (p.getState() == PaymentState.EXPIRED) {
        it.remove();
    }
}

// Cách 2: removeIf (Java 8+)
payments.removeIf(p -> p.getState() == PaymentState.EXPIRED);

// Cách 3: Stream collect
List<Payment> active = payments.stream()
    .filter(p -> p.getState() != PaymentState.EXPIRED)
    .toList();
```

---

## 3. Spring Boot Errors

### 3.1 `Port 8080 already in use`

```
***************************
APPLICATION FAILED TO START
***************************

Description:
Web server failed to start. Port 8080 was already in use.
```

**Fix:**

```bash
# Windows — tìm process dùng port
netstat -ano | findstr :8080
# Output: TCP  0.0.0.0:8080  ...  LISTENING  12345
taskkill /PID 12345 /F

# Hoặc đổi port
./gradlew bootRun --args='--server.port=9090'
```

### 3.2 `Could not autowire` / `NoSuchBeanDefinitionException`

```
No qualifying bean of type 'com.fraud.api.service.PaymentService' available
```

**Nguyên nhân và fix:**

```java
// Nguyên nhân 1: Thiếu @Service
public class PaymentService { }  // ❌

// Fix:
@Service
public class PaymentService { }  // ✓

// Nguyên nhân 2: Class ở package không được scan
// (Ngoài package của @SpringBootApplication)

// Fix: Di chuyển class vào package đúng
// Hoặc explicit scan:
@SpringBootApplication(scanBasePackages = {"com.fraud", "com.external"})

// Nguyên nhân 3: Profile không active
@Service
@Profile("prod")
public class PaymentService { }

// Fix: Chạy với đúng profile
// --spring.profiles.active=prod
```

### 3.3 `Circular reference`

```
The dependencies of some of the beans form a cycle:
┌─────┐
|  serviceA
↑     ↓
|  serviceB
└─────┘
```

**Fix:** Xem [03-dependency-injection.md](./03-dependency-injection.md#7-circular-dependency).

### 3.4 `Failed to configure DataSource`

```
Failed to configure a DataSource: 'url' attribute is not specified and 
no embedded datasource could be configured.
```

**Fix:**

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fraud
    username: fraud
    password: fraud
    driver-class-name: org.postgresql.Driver
```

### 3.5 `Validation failed for classes`

```
Validation failed for classes [com.fraud.api.domain.Payment] during prepare:
SchemaManagementException: Schema-validation: missing column [amount] in table [payments]
```

**Nguyên nhân:** Entity mismatch với DB schema.

**Fix:**

```sql
-- Thêm migration
-- V5__add_amount_column.sql
ALTER TABLE payments ADD COLUMN amount BIGINT NOT NULL DEFAULT 0;
```

---

## 4. Database Errors

### 4.1 `Connection refused`

```
org.postgresql.util.PSQLException: Connection to localhost:5432 refused
```

**Fix:**

```bash
# Check PostgreSQL running
docker ps | grep postgres

# Start if not
docker compose up -d postgres

# Test connection
docker exec -it fraud-postgres psql -U fraud -d fraud
```

### 4.2 `Duplicate key violates unique constraint`

```
ERROR: duplicate key value violates unique constraint "uk_merchant_idempotency"
Detail: Key (merchant_id, idempotency_key)=(m-001, idem-123) already exists.
```

**Fix: Handle gracefully**

```java
try {
    paymentRepo.save(payment);
} catch (DataIntegrityViolationException e) {
    // Race condition — another request inserted first
    Payment existing = paymentRepo
        .findByMerchantIdAndIdempotencyKey(
            payment.getMerchantId(), 
            payment.getIdempotencyKey())
        .orElseThrow();
    return existing;
}
```

### 4.3 `Connection pool exhausted`

```
HikariPool-1 - Connection is not available, request timed out after 30000ms
```

**Nguyên nhân:**
- Transaction kéo dài
- Connection leak
- Pool quá nhỏ

**Fix:**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50  # Tăng
      leak-detection-threshold: 60000  # Debug leak (log nếu giữ connection >60s)
```

```java
// Giảm transaction scope
@Service
public class PaymentService {
    
    // ❌ BAD: Transaction wraps HTTP call
    @Transactional
    public void process() {
        Payment p = repo.save(payment);
        webhookService.callExternalAPI(p);  // ← HTTP call trong transaction!
    }
    
    // ✓ GOOD: Split transaction
    public void process() {
        Payment p = saveWithTransaction(payment);
        webhookService.callExternalAPI(p);  // Outside transaction
    }
    
    @Transactional
    private Payment saveWithTransaction(Payment p) {
        return repo.save(p);
    }
}
```

### 4.4 `Row was updated or deleted by another transaction`

```
OptimisticLockException: Row was updated or deleted by another transaction
```

**Fix:** Retry logic

```java
@Retryable(
    retryFor = OptimisticLockException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 100)
)
public void updatePayment(String id, PaymentState newState) {
    Payment p = repo.findById(id).orElseThrow();
    p.setState(newState);
    repo.save(p);  // Sẽ throw nếu version conflict
}
```

### 4.5 `LazyInitializationException`

```
could not initialize proxy - no Session
```

**Fix:**

```java
// ❌ BAD: Transaction đóng trước khi access
public Payment getPayment(String id) {
    Payment p = repo.findById(id).get();
    return p;  // Access lazy field → LazyInitializationException
}

// ✓ GOOD: Keep transaction open
@Transactional(readOnly = true)
public Payment getPayment(String id) {
    Payment p = repo.findById(id).get();
    p.getEvents().size();  // Trigger loading
    return p;
}

// ✓ BETTER: JOIN FETCH
@Query("SELECT p FROM Payment p JOIN FETCH p.events WHERE p.paymentId = :id")
Optional<Payment> findByIdWithEvents(@Param("id") String id);
```

---

## 5. Kafka Errors

### 5.1 `Connection refused to localhost:9092`

**Fix:**

```bash
# Check Kafka running
docker ps | grep kafka

# Start
docker compose --profile kafka up -d
```

### 5.2 `Offset out of range`

```
Offsets out of range with no configured reset policy for partitions
```

**Fix:**

```yaml
spring:
  kafka:
    consumer:
      auto-offset-reset: earliest  # Or 'latest'
```

### 5.3 Consumer Lag

```bash
# Check lag
docker exec fraud-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group fraud-group \
    --describe

# GROUP    TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# fraud    risk.ingest 0          100             5000            4900 ← HIGH LAG!
```

**Fix:**
- Tăng consumer concurrency
- Optimize processing speed
- Thêm partitions (và consumers)

```yaml
spring:
  kafka:
    listener:
      concurrency: 6  # Tăng
```

### 5.4 `Rebalancing`

```
INFO: [Consumer] Attempt to heartbeat failed since group is rebalancing
```

**Nguyên nhân:** Consumer bị coi là dead (xử lý quá lâu).

**Fix:**

```yaml
spring:
  kafka:
    consumer:
      max-poll-interval-ms: 600000  # Max time giữa 2 poll (default 5 min)
      max-poll-records: 100          # Số records mỗi batch (nhỏ hơn → xử lý nhanh)
```

---

## 6. RabbitMQ Errors

### 6.1 `Connection refused`

```bash
# Check RabbitMQ
docker ps | grep rabbitmq

# Start
docker compose --profile rabbitmq up -d

# UI: http://localhost:15672 (fraud/fraud)
```

### 6.2 `Queue not found`

**Fix:** Declare queue trong config

```java
@Configuration
public class RabbitMQConfig {
    @Bean
    public Queue merchantQueue() {
        return QueueBuilder.durable("merchant.m-001")
            .withArgument("x-dead-letter-exchange", "risk.dlx")
            .build();
    }
}
```

### 6.3 `Message unacknowledged → requeue loop`

```
WARN: Received message 100 times (stuck in redelivery loop)
```

**Fix:**

```java
@RabbitListener(queues = "merchant.queue")
public void consume(Message msg) {
    try {
        processMessage(msg);
    } catch (RuntimeException e) {
        // ❌ Re-throw → requeue → infinite loop
        throw e;
    }
}

// ✓ Handle gracefully — send to DLQ
@RabbitListener(queues = "merchant.queue")
public void consume(Message msg, Channel channel) throws IOException {
    try {
        processMessage(msg);
        channel.basicAck(msg.getDeliveryTag(), false);
    } catch (PermanentException e) {
        // Send to DLQ
        channel.basicReject(msg.getDeliveryTag(), false);
    } catch (TemporaryException e) {
        // Retry
        channel.basicNack(msg.getDeliveryTag(), false, true);
    }
}
```

---

## 7. Build Errors (Gradle)

### 7.1 `Could not resolve dependency`

```
Could not resolve org.springframework.boot:spring-boot-starter:3.99.0
```

**Fix:**
```bash
# Refresh
./gradlew build --refresh-dependencies

# Check version exists (search Maven Central)
# Fix version in build.gradle.kts
```

### 7.2 `Unsupported class file major version 65`

```
BUG! Unable to read class file: Unsupported class file major version 65
```

**Nguyên nhân:** JAR built với Java version mới hơn runtime.

**Fix:**

```bash
# Major version 65 = Java 21
# Install Java 21
java -version  # Must match

# Or downgrade dependency to version supporting Java 17
```

### 7.3 `Execution failed for task ':test'`

```bash
# Xem chi tiết
./gradlew test --info

# Hoặc
./gradlew test --stacktrace

# Skip test nếu cần
./gradlew build -x test
```

---

## 8. Docker Errors

### 8.1 `Cannot connect to the Docker daemon`

**Fix:**
```
Mở Docker Desktop, chờ icon chuyển xanh
```

### 8.2 `Ports are not available: port is already allocated`

```
Error: bind: address already in use
```

**Fix:**
```bash
# Find process using port
netstat -ano | findstr :5432  # Windows

# Kill or change port in docker-compose.yml
ports:
  - "5433:5432"  # Map to different host port
```

### 8.3 `Container exited with code 1`

```bash
# Xem logs
docker logs fraud-postgres

# Common causes:
# - Volume permissions
# - Config error
# - OOM
```

### 8.4 `No space left on device`

```bash
# Clean up
docker system prune -a
docker volume prune
```

---

## 9. Performance Issues

### 9.1 Slow startup

**Debug:**

```bash
# Measure
./gradlew bootRun --args='--spring.main.web-environment=true --debug'
```

**Common causes:**
- Quá nhiều auto-config → disable unused
- Database migration chạy → chấp nhận lần đầu
- Component scan quá rộng

**Fix:**
```java
@SpringBootApplication(
    scanBasePackages = {"com.fraud.api"},  // Scan specific
    exclude = {MongoAutoConfiguration.class}  // Disable unused
)
```

### 9.2 High CPU

```bash
# Thread dump
jstack <PID> > threads.txt

# Tìm threads RUNNING
grep -A 20 "RUNNABLE" threads.txt

# Profile
async-profiler -d 30 <PID>
```

### 9.3 High memory

```bash
# Heap dump
jmap -dump:live,format=b,file=heap.hprof <PID>

# Histogram
jmap -histo <PID> | head -20

# Analyze với MAT hoặc VisualVM
```

---

## 10. Quick Fix Reference

| Lỗi | Fix nhanh |
|-----|-----------|
| `Port 8080 in use` | `netstat -ano \| findstr 8080` → `taskkill /PID <PID> /F` |
| `NoSuchBeanDefinitionException` | Thêm `@Service` / check package scan |
| `NullPointerException` | Check null / dùng Optional |
| `Connection refused PostgreSQL` | `docker compose up -d postgres` |
| `LazyInitializationException` | `@Transactional(readOnly = true)` hoặc JOIN FETCH |
| `Circular reference` | Refactor hoặc `@Lazy` |
| `Duplicate key` | Handle `DataIntegrityViolationException` |
| `Kafka offset out of range` | `auto-offset-reset: earliest` |
| `Dependency not found` | `./gradlew build --refresh-dependencies` |
| `Gradle wrapper not found` | Copy `gradle-wrapper.jar` từ repo khác |

---

## 11. Prevention Checklist

Trước khi commit code, kiểm tra:

```
☐ Không dùng field injection (@Autowired trên field)
☐ Không catch Exception chung chung (catch specific)
☐ Luôn log exception với stack trace: log.error("msg", e)
☐ Null checks ở public API
☐ @Transactional trên service, không controller
☐ Database queries có index
☐ Không dùng FetchType.EAGER
☐ Không commit .env, secrets
☐ Test pass: ./gradlew test
☐ Build pass: ./gradlew build
```

---

## 📚 Tiếp theo

→ [`10-logging-observability.md`](./10-logging-observability.md) — Logging & Observability
