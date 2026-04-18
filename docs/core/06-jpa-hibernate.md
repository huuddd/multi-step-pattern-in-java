# 06. JPA & Hibernate — Database từ A-Z

## 🎯 Mục tiêu

- Hiểu JPA, Hibernate, Spring Data JPA khác nhau
- Hiểu entity, transaction, connection pool
- Biết debug slow query, N+1 problem
- Tối ưu performance DB

---

## 1. Các lớp abstraction

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DATABASE ACCESS LAYERS                              │
└─────────────────────────────────────────────────────────────────────────┘

    Your code
    ┌───────────────────────────────────────────────────────────────┐
    │  paymentRepository.save(payment)                              │
    └───────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────────┐
    │  Spring Data JPA (interface magic)                            │
    │  - PaymentRepository extends JpaRepository                    │
    │  - Auto-generate query from method name                       │
    └───────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────────┐
    │  JPA (Jakarta Persistence API - Specification)                │
    │  - @Entity, @Id, @Column                                      │
    │  - EntityManager, persist(), find()                           │
    └───────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────────┐
    │  Hibernate (JPA Implementation)                               │
    │  - Map entity → SQL                                           │
    │  - Cache, lazy loading, dirty checking                        │
    └───────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────────┐
    │  JDBC (Java Database Connectivity)                            │
    │  - Low-level DB driver API                                    │
    │  - Connection, PreparedStatement, ResultSet                   │
    └───────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────────┐
    │  HikariCP (Connection Pool)                                   │
    │  - Reuse connections instead of creating new                  │
    └───────────────────────────┬───────────────────────────────────┘
                                │
                                ▼
    ┌───────────────────────────────────────────────────────────────┐
    │  PostgreSQL (Actual database)                                 │
    └───────────────────────────────────────────────────────────────┘
```

---

## 2. Entity — JPA mapping

### 2.1 Basic entity

```java
@Entity
@Table(name = "payments")
public class Payment {
    
    @Id
    @Column(name = "payment_id")
    private String paymentId;
    
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;
    
    @Column(nullable = false)
    private Long amount;
    
    @Enumerated(EnumType.STRING)  // Lưu "ALLOW" thay vì số 0
    private Decision decision;
    
    @Column(name = "created_at")
    private Instant createdAt;
    
    // getters, setters, equals, hashCode, toString
}
```

### 2.2 Annotations quan trọng

| Annotation | Mục đích |
|------------|----------|
| `@Entity` | Đánh dấu class là JPA entity |
| `@Table(name = "...")` | Map tới table |
| `@Id` | Primary key |
| `@GeneratedValue` | Auto-generate ID |
| `@Column` | Map field → column |
| `@Enumerated(EnumType.STRING)` | Lưu enum dạng string |
| `@Temporal` | Legacy Date type |
| `@Lob` | Large object (BLOB, CLOB) |
| `@Transient` | Không persist |
| `@Version` | Optimistic locking |

### 2.3 ID Generation

```java
// Option 1: Manual (như dự án này)
@Id
private String paymentId;  // Client cung cấp

// Option 2: Auto-generate
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)  // DB auto-increment
private Long id;

// Option 3: UUID
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;

// Option 4: Sequence (PostgreSQL, Oracle)
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_seq")
@SequenceGenerator(name = "payment_seq", sequenceName = "payment_sequence")
private Long id;
```

### 2.4 Relationships

```java
// One-to-Many
@Entity
public class Payment {
    @Id
    private String paymentId;
    
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RiskEvent> events;
}

@Entity
public class RiskEvent {
    @Id
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;
}
```

**⚠️ QUAN TRỌNG:** Luôn dùng `FetchType.LAZY` để tránh load quá nhiều data.

---

## 3. Spring Data JPA

### 3.1 Repository interface

```java
public interface PaymentRepository extends JpaRepository<Payment, String> {
    //                                        └─ Entity └─ ID type
}
```

**Tự động có:**
- `save(entity)`
- `findById(id)`
- `findAll()`
- `deleteById(id)`
- `count()`
- `existsById(id)`
- v.v.

### 3.2 Query Methods — Magic từ method name

```java
public interface PaymentRepository extends JpaRepository<Payment, String> {
    
    // SELECT * FROM payments WHERE merchant_id = ?
    List<Payment> findByMerchantId(String merchantId);
    
    // SELECT * FROM payments WHERE merchant_id = ? AND idempotency_key = ?
    Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String key);
    
    // SELECT * FROM payments WHERE state = ? ORDER BY created_at DESC
    List<Payment> findByStateOrderByCreatedAtDesc(PaymentState state);
    
    // SELECT * FROM payments WHERE amount > ?
    List<Payment> findByAmountGreaterThan(Long amount);
    
    // SELECT COUNT(*) FROM payments WHERE state = ?
    long countByState(PaymentState state);
    
    // DELETE FROM payments WHERE created_at < ?
    void deleteByCreatedAtBefore(Instant cutoff);
}
```

**Keywords:**

| Keyword | SQL |
|---------|-----|
| `findBy` | SELECT WHERE |
| `countBy` | SELECT COUNT |
| `deleteBy` | DELETE WHERE |
| `And`, `Or` | AND, OR |
| `GreaterThan`, `LessThan` | >, < |
| `Between` | BETWEEN |
| `Like`, `Containing` | LIKE |
| `OrderBy...Asc/Desc` | ORDER BY |

### 3.3 @Query — Custom queries

```java
public interface PaymentRepository extends JpaRepository<Payment, String> {
    
    // JPQL (Object-oriented)
    @Query("SELECT p FROM Payment p WHERE p.state = :state AND p.createdAt > :since")
    List<Payment> findRecentByState(
            @Param("state") PaymentState state,
            @Param("since") Instant since);
    
    // Native SQL
    @Query(value = "SELECT * FROM payments WHERE amount > ?1 LIMIT ?2", nativeQuery = true)
    List<Payment> findLargePayments(Long amount, int limit);
    
    // Update
    @Modifying
    @Query("UPDATE Payment p SET p.state = :state WHERE p.paymentId = :id")
    int updateState(@Param("id") String id, @Param("state") PaymentState state);
}
```

---

## 4. Transactions

### 4.1 @Transactional

```java
@Service
public class PaymentService {
    
    @Transactional  // Auto begin/commit/rollback
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        Payment p = new Payment();
        // ... setup
        
        paymentRepository.save(p);           // SQL INSERT
        riskEventRepository.save(event1);    // SQL INSERT
        riskEventRepository.save(event2);    // SQL INSERT
        
        // Tất cả trong 1 transaction:
        // - Thành công → COMMIT
        // - Throw exception → ROLLBACK
    }
}
```

### 4.2 Transaction Propagation

```java
@Transactional(propagation = Propagation.REQUIRED)  // DEFAULT
// Nếu đã có transaction → join. Nếu chưa → tạo mới.

@Transactional(propagation = Propagation.REQUIRES_NEW)
// LUÔN tạo transaction mới (suspend outer transaction)

@Transactional(propagation = Propagation.NESTED)
// Nested transaction với savepoint

@Transactional(propagation = Propagation.NEVER)
// PHẢI không có transaction, nếu có → throw
```

**Ví dụ REQUIRES_NEW:**

```java
@Service
public class PaymentService {
    
    @Autowired
    private AuditService auditService;
    
    @Transactional
    public void authorize(Request req) {
        paymentRepo.save(payment);
        
        try {
            auditService.log(payment);  // REQUIRES_NEW
        } catch (Exception e) {
            // Audit fail không ảnh hưởng payment
            log.error("Audit failed", e);
        }
        
        // Payment vẫn được commit
    }
}

@Service
public class AuditService {
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Payment p) {
        auditRepo.save(new AuditLog(p));
    }
}
```

### 4.3 Transaction Isolation

```java
@Transactional(isolation = Isolation.READ_COMMITTED)  // DEFAULT trong PostgreSQL
```

| Level | Dirty Read | Non-repeatable Read | Phantom Read |
|-------|------------|---------------------|--------------|
| READ_UNCOMMITTED | ✗ | ✗ | ✗ |
| READ_COMMITTED | ✓ | ✗ | ✗ |
| REPEATABLE_READ | ✓ | ✓ | ✗ |
| SERIALIZABLE | ✓ | ✓ | ✓ |

### 4.4 Rollback Rules

```java
// Default: rollback chỉ khi RuntimeException
@Transactional
public void method() {
    throw new IOException();  // CHECKED exception → KHÔNG rollback!
}

// Explicit rollback
@Transactional(rollbackFor = Exception.class)
public void method() {
    throw new IOException();  // Bây giờ rollback
}

// Explicit no-rollback
@Transactional(noRollbackFor = ValidationException.class)
public void method() {
    throw new ValidationException();  // Không rollback
}
```

### 4.5 ⚠️ Common Pitfall: Self-invocation

```java
@Service
public class PaymentService {
    
    public void outer() {
        this.inner();  // ❌ @Transactional KHÔNG HIỆU LỰC!
    }
    
    @Transactional
    public void inner() {
        // ...
    }
}
```

**Tại sao?** Spring dùng proxy AOP. `this.inner()` bypass proxy.

**Fix:**
```java
@Service
public class PaymentService {
    
    @Autowired
    private PaymentService self;  // Inject proxy
    
    public void outer() {
        self.inner();  // ✓ Qua proxy, @Transactional hoạt động
    }
    
    @Transactional
    public void inner() { }
}
```

---

## 5. Connection Pool — HikariCP

### 5.1 Connection pool là gì?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    WITHOUT CONNECTION POOL                             │
└─────────────────────────────────────────────────────────────────────────┘

    Request 1 → Open connection (100ms) → Query → Close connection
    Request 2 → Open connection (100ms) → Query → Close connection
    Request 3 → Open connection (100ms) → Query → Close connection
    
    → Mỗi request tốn 100ms chỉ để mở connection!


┌─────────────────────────────────────────────────────────────────────────┐
│                    WITH CONNECTION POOL                                │
└─────────────────────────────────────────────────────────────────────────┘

    Pool (10 connections, luôn sẵn sàng)
    ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
    │ C1   │ │ C2   │ │ C3   │ │ ...  │
    └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘
       │        │        │        │
       ▼        ▼        ▼        ▼
    Req 1    Req 2    Req 3    Req N
    
    Request → Borrow connection (1ms) → Query → Return to pool
    → Fast!
```

### 5.2 Config

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fraud
    username: fraud
    password: fraud
    hikari:
      maximum-pool-size: 20       # Max connections
      minimum-idle: 5             # Min idle connections
      connection-timeout: 30000   # Max wait for connection (ms)
      idle-timeout: 600000        # Close idle connection after (ms)
      max-lifetime: 1800000       # Max connection age (ms)
      leak-detection-threshold: 60000  # Log if connection held > 60s
```

### 5.3 Sizing guideline

```
connections = ((core_count × 2) + effective_spindle_count)

Example: 8-core CPU, SSD (1 spindle)
connections = (8 × 2) + 1 = 17

→ maximum-pool-size = 20 là hợp lý
```

### 5.4 Monitor

```bash
# Actuator endpoint
curl http://localhost:8080/actuator/metrics/hikaricp.connections

# Metrics:
# hikaricp.connections.active     (current active)
# hikaricp.connections.idle       (current idle)
# hikaricp.connections.pending    (waiting for connection)
# hikaricp.connections.timeout    (connection timeout count)
```

---

## 6. N+1 Problem

### 6.1 Vấn đề

```java
@Entity
public class Payment {
    @OneToMany(mappedBy = "payment")
    private List<RiskEvent> events;
}

// Code:
List<Payment> payments = paymentRepo.findAll();  // 1 query
for (Payment p : payments) {
    p.getEvents().size();  // N queries (1 per payment)
}

// Total: 1 + N queries! (Slow!)
```

### 6.2 Fix: JOIN FETCH

```java
// JPQL
@Query("SELECT p FROM Payment p JOIN FETCH p.events")
List<Payment> findAllWithEvents();

// EntityGraph
@EntityGraph(attributePaths = {"events"})
List<Payment> findAll();
```

**Result:** 1 query with JOIN.

### 6.3 Detect N+1

```yaml
# Log SQL queries
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.type.descriptor.sql: TRACE
```

---

## 7. Optimistic Locking

### 7.1 Vấn đề: Concurrent update

```
Thread 1:                          Thread 2:
  Read Payment (version=1)           Read Payment (version=1)
  Modify amount = 100               Modify amount = 200
  Save                              Save  ← Overwrite Thread 1!
```

### 7.2 Fix: @Version

```java
@Entity
public class Payment {
    @Id
    private String id;
    
    @Version
    private Long version;  // Auto-increment on update
}

// Hibernate auto-generate:
// UPDATE payments SET ..., version = 2 WHERE id = ? AND version = 1
// Nếu version không match → OptimisticLockException
```

---

## 8. Flyway — Database Migrations

### 8.1 Setup

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### 8.2 Migration files

```
src/main/resources/db/migration/
├── V1__create_tables.sql
├── V2__add_indexes.sql
├── V3__add_decision_column.sql
└── V4__seed_data.sql
```

```sql
-- V1__create_tables.sql
CREATE TABLE payments (
    payment_id VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);
```

**Quy tắc:**
- `V{version}__{description}.sql`
- Immutable — không sửa migration đã apply
- Thêm migration mới thay vì sửa cũ

---

## 9. Common Issues

### 9.1 LazyInitializationException

```java
// ❌ DON'T
@GetMapping("/{id}")
public Payment getPayment(@PathVariable String id) {
    Payment p = repo.findById(id).get();  // Transaction đóng
    return p;  // Try access lazy field → LazyInitializationException
}

// ✅ DO — Eager fetch hoặc DTO
@GetMapping("/{id}")
@Transactional(readOnly = true)  // Giữ transaction
public Payment getPayment(@PathVariable String id) {
    Payment p = repo.findById(id).get();
    p.getEvents().size();  // Trigger loading trong transaction
    return p;
}
```

### 9.2 Slow query

**Debug:**
```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG
```

```sql
-- Xem execution plan
EXPLAIN ANALYZE SELECT * FROM payments WHERE merchant_id = 'm-1';

-- Thêm index
CREATE INDEX idx_payments_merchant ON payments(merchant_id);
```

### 9.3 Connection pool exhausted

```
HikariPool-1 - Connection is not available, request timed out after 30000ms
```

**Nguyên nhân:**
- Transaction kéo dài quá
- Connection leak (quên close)
- Pool size quá nhỏ

**Fix:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50  # Tăng lên
      leak-detection-threshold: 60000  # Phát hiện leak
```

---

## 10. Best Practices

### 10.1 DO ✅

```java
// ✅ Constructor injection
@Service
public class PaymentService {
    private final PaymentRepository repo;
    public PaymentService(PaymentRepository repo) { this.repo = repo; }
}

// ✅ Transaction in service layer
@Service
@Transactional
public class PaymentService { }

// ✅ Read-only for queries
@Transactional(readOnly = true)
public Payment findById(String id) { }

// ✅ Use DTOs for API responses (not entities)
public record PaymentResponse(String id, String status) { }
```

### 10.2 DON'T ❌

```java
// ❌ Transaction in controller
@RestController
public class PaymentController {
    @Transactional  // Bad practice
    @PostMapping
    public void create(...) { }
}

// ❌ Return entity from API
@GetMapping("/{id}")
public Payment getPayment(String id) {
    return repo.findById(id).get();  // Expose internal structure
}

// ❌ FetchType.EAGER
@OneToMany(fetch = FetchType.EAGER)  // Load mọi lúc, slow!
```

---

## 11. Kiểm tra hiểu bài

1. JPA, Hibernate, Spring Data JPA khác nhau thế nào?
2. `@Transactional` không hoạt động khi nào?
3. N+1 problem là gì, fix thế nào?
4. Tại sao cần connection pool?
5. Khi nào dùng `FetchType.LAZY` vs `EAGER`?

### Đáp án

1. JPA: specification. Hibernate: implementation. Spring Data JPA: higher-level abstraction on top of JPA
2. Self-invocation, private method, CHECKED exception (default rollback chỉ Runtime)
3. Load list → iterate load related entity → N+1 queries. Fix bằng JOIN FETCH hoặc @EntityGraph
4. Tạo connection tốn ~100ms. Pool reuse connections → fast (1ms)
5. LAZY cho relationships (default). EAGER chỉ khi chắc chắn cần — thường gây vấn đề

---

## 📚 Tiếp theo

→ [`07-threading-model.md`](./07-threading-model.md) — Threading & Async
