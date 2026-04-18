# 04. Project Structure — Dự án này có gì?

## 🎯 Mục tiêu

- Hiểu từng folder/file trong dự án làm gì
- Biết khi thêm tính năng mới, code nên đặt đâu
- Biết flow dữ liệu xuyên qua các lớp

---

## 1. Tổng quan

```
multi-step-pattern-in-java/
├── docs/                       ← Tài liệu
│   ├── core/                   ← 🔥 Kiến thức core (bạn đang ở đây)
│   ├── java-concepts/          ← Concepts chuyên sâu
│   ├── variants/               ← Design notes từng variant
│   ├── benchmark/              ← Kết quả benchmark
│   ├── 00-overview.md          ← Architecture overview
│   ├── PLAN.md                 ← Task plan
│   └── REPORT.md               ← Technical report
│
└── source/                     ← Source code
    ├── common/                 ← Shared module (DTOs, domain)
    ├── api-service/            ← Main Spring Boot app
    ├── bench/                  ← Gatling benchmarks
    ├── grafana/                ← Grafana dashboards
    ├── docker-compose.yml      ← Infrastructure services
    ├── build.gradle.kts        ← Root build config
    └── settings.gradle.kts     ← Multi-module config
```

---

## 2. Module `common/`

**Mục đích:** Chứa code được share giữa các module.

```
common/
└── src/main/java/com/fraud/common/
    ├── domain/
    │   ├── Decision.java          ← Enum: ALLOW, REVIEW, BLOCK
    │   ├── PaymentState.java      ← Enum: PENDING, DECIDED, REVIEW, BLOCKED
    │   ├── PipelineStep.java      ← Enum: INGEST, FEATURE, MODEL, RULE, DECISION
    │   └── StepStatus.java        ← Enum: STARTED, RUNNING, DONE, FAILED, ...
    │
    ├── dto/
    │   ├── AuthorizeRequest.java  ← Request DTO
    │   └── AuthorizeResponse.java ← Response DTO
    │
    └── event/
        └── PaymentEvent.java      ← Event cho Kafka/RabbitMQ
```

**Tại sao tách riêng?**
- Nhiều module cần dùng (api-service, variants, bench)
- Tránh circular dependency
- Dễ maintain

---

## 3. Module `api-service/` — Main App

### 3.1 Entry Point

```
api-service/src/main/java/com/fraud/api/
└── FraudDetectionApplication.java  ← main() method
```

```java
@SpringBootApplication
public class FraudDetectionApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionApplication.class, args);
    }
}
```

### 3.2 Cấu trúc thư mục (Layered Architecture)

```
com.fraud.api/
│
├── controller/        ← 🌐 HTTP Layer (REST endpoints)
│   ├── PaymentController.java
│   ├── ReviewController.java
│   └── MetricsController.java
│
├── service/           ← 💼 Business Logic Layer
│   ├── PaymentService.java
│   ├── ReviewService.java
│   ├── WebhookService.java
│   └── MetricsService.java
│
├── pipeline/          ← ⚙️ Multi-step Pipeline Engine
│   ├── FraudDetectionPipeline.java
│   ├── PipelineContext.java
│   ├── PipelineStep.java
│   └── steps/
│       ├── IngestStep.java
│       ├── FeatureStep.java
│       ├── ModelStep.java
│       ├── RuleStep.java
│       ├── DecisionStep.java
│       └── AsyncModelStep.java    ← Variant B (thread pool)
│
├── repository/        ← 🗄️ Data Access Layer
│   ├── PaymentRepository.java
│   └── RiskEventRepository.java
│
├── domain/            ← 📦 JPA Entities
│   ├── Payment.java
│   └── RiskEvent.java
│
├── kafka/             ← 📨 Variant C (Kafka Pipeline)
│   ├── KafkaTopicConfig.java
│   ├── KafkaProducerConfig.java
│   ├── KafkaConsumerConfig.java
│   └── consumer/
│       ├── IngestConsumer.java
│       ├── FeatureConsumer.java
│       ├── ModelConsumer.java
│       ├── RuleConsumer.java
│       └── DlqHandler.java
│
├── rabbitmq/          ← 🐰 Variant D (RabbitMQ Per-Merchant)
│   ├── RabbitMQConfig.java
│   ├── MerchantQueueManager.java
│   ├── MerchantMessagePublisher.java
│   ├── MerchantConsumer.java
│   └── PerMerchantRateLimiter.java
│
├── config/            ← ⚙️ Spring Configuration
│   ├── ThreadPoolConfig.java
│   └── ...
│
└── exception/         ← ⚠️ Custom Exceptions
    ├── PaymentNotFoundException.java
    ├── IdempotencyConflictException.java
    └── InvalidStateTransitionException.java
```

---

## 4. Data Flow — Request đi qua các lớp nào?

```
┌─────────────────────────────────────────────────────────────────────────┐
│               REQUEST FLOW (Variant A - Synchronous)                   │
└─────────────────────────────────────────────────────────────────────────┘

    Client
      │
      │ POST /payments/authorize
      │ Content-Type: application/json
      │ Body: AuthorizeRequest
      ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 1. PaymentController.authorize()                                │
    │    - Validate request (@Valid)                                  │
    │    - Call service                                               │
    └───────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 2. PaymentService.authorize()                                   │
    │    - Check idempotency (repository)                             │
    │    - If exists → return cached                                  │
    │    - If not → execute pipeline                                  │
    └───────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 3. FraudDetectionPipeline.execute()                             │
    │    - Run steps: INGEST → FEATURE → MODEL → RULE → DECISION     │
    └───────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 4. IngestStep.execute(ctx)  → Validate                          │
    │ 5. FeatureStep.execute(ctx) → Extract features                  │
    │ 6. ModelStep.execute(ctx)   → Risk score                        │
    │ 7. RuleStep.execute(ctx)    → Apply rules                       │
    │ 8. DecisionStep.execute(ctx)→ Final decision                    │
    └───────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 9. PaymentRepository.save()                                     │
    │    - JPA → Hibernate → JDBC → PostgreSQL                        │
    └───────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 10. WebhookService.send() (if needed)                           │
    │     - Call merchant webhook URL                                 │
    └───────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │ 11. Return AuthorizeResponse to client                          │
    └─────────────────────────────────────────────────────────────────┘
```

---

## 5. Từng file quan trọng

### 5.1 PaymentController.java

**Vai trò:** Nhận HTTP request, validate, trả response.

```java
@RestController
@RequestMapping("/payments")
public class PaymentController {
    
    private final PaymentService paymentService;
    
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
    
    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(
            @Valid @RequestBody AuthorizeRequest request) {
        
        AuthorizeResponse response = paymentService.authorize(request);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{paymentId}/status")
    public ResponseEntity<PaymentStatusResponse> getStatus(
            @PathVariable String paymentId) {
        // ...
    }
}
```

**Điểm cần chú ý:**
- `@RestController` = `@Controller` + `@ResponseBody` (auto JSON)
- `@Valid` → bật validation cho request
- Constructor injection của `PaymentService`

### 5.2 PaymentService.java

**Vai trò:** Business logic, orchestrate pipeline.

```java
@Service
@Transactional  // Auto wrap trong DB transaction
public class PaymentService {
    
    private final PaymentRepository repository;
    private final FraudDetectionPipeline pipeline;
    
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        // 1. Idempotency check
        Optional<Payment> existing = repository
                .findByMerchantIdAndIdempotencyKey(
                        request.merchantId(), 
                        request.idempotencyKey());
        
        if (existing.isPresent()) {
            return mapToResponse(existing.get());
        }
        
        // 2. Execute pipeline
        Payment payment = createPayment(request);
        PipelineContext ctx = new PipelineContext(payment);
        pipeline.execute(ctx);
        
        // 3. Save result
        payment = repository.save(payment);
        
        return mapToResponse(payment);
    }
}
```

**Điểm cần chú ý:**
- `@Transactional` → Spring tự begin/commit transaction
- Idempotency check trước khi process
- Tất cả DB operations trong 1 transaction

### 5.3 FraudDetectionPipeline.java

**Vai trò:** Orchestrate các pipeline steps.

```java
@Component
public class FraudDetectionPipeline {
    
    private final List<PipelineStep> steps;
    
    // Spring tự inject TẤT CẢ beans implement PipelineStep
    public FraudDetectionPipeline(List<PipelineStep> steps) {
        this.steps = steps.stream()
                .sorted(Comparator.comparingInt(PipelineStep::order))
                .toList();
    }
    
    public PipelineContext execute(PipelineContext ctx) {
        for (PipelineStep step : steps) {
            ctx = step.execute(ctx);
            
            if (ctx.isTerminated()) {
                break;  // BLOCK decision → dừng pipeline
            }
        }
        return ctx;
    }
}
```

**Điểm cần chú ý:**
- Spring inject **List of beans** cùng interface
- Steps chạy theo thứ tự `order()`
- Early termination nếu BLOCK

### 5.4 PipelineStep.java

**Vai trò:** Interface cho các bước pipeline.

```java
public interface PipelineStep {
    
    int order();  // Thứ tự trong pipeline
    
    PipelineContext execute(PipelineContext ctx);
    
    default String name() {
        return getClass().getSimpleName();
    }
}
```

### 5.5 IngestStep.java (ví dụ 1 step)

```java
@Component
public class IngestStep implements PipelineStep {
    
    @Override
    public int order() {
        return 1;  // Bước đầu tiên
    }
    
    @Override
    public PipelineContext execute(PipelineContext ctx) {
        Payment payment = ctx.getPayment();
        
        // Validate
        if (payment.getAmount() <= 0) {
            throw new ValidationException("Amount must be positive");
        }
        
        // Normalize
        payment.setCurrency(payment.getCurrency().toUpperCase());
        
        return ctx;
    }
}
```

### 5.6 PaymentRepository.java

**Vai trò:** Data access abstraction.

```java
public interface PaymentRepository extends JpaRepository<Payment, String> {
    
    // Spring Data JPA auto-generate implementation từ method name
    Optional<Payment> findByMerchantIdAndIdempotencyKey(
            String merchantId, String idempotencyKey);
    
    // Custom query
    @Query("SELECT p FROM Payment p WHERE p.state = :state AND p.createdAt > :since")
    List<Payment> findRecentByState(
            @Param("state") PaymentState state, 
            @Param("since") Instant since);
}
```

**Magic của Spring Data JPA:**
- Interface → Spring tự implement
- Method name → SQL query auto-generated
- `findByXxxAndYyy` → `WHERE xxx = ? AND yyy = ?`

### 5.7 Payment.java (Entity)

```java
@Entity
@Table(name = "payments")
public class Payment {
    
    @Id
    private String paymentId;
    
    @Column(name = "merchant_id", nullable = false)
    private String merchantId;
    
    @Column(nullable = false)
    private Long amount;
    
    @Enumerated(EnumType.STRING)
    private PaymentState state;
    
    @Enumerated(EnumType.STRING)
    private Decision decision;
    
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;
    
    @Column(name = "created_at")
    private Instant createdAt;
    
    // getters, setters, equals, hashCode...
}
```

**Annotations:**
- `@Entity` → JPA nhận đây là entity
- `@Table` → map tới table trong DB
- `@Id` → primary key
- `@Column` → map field → column
- `@Enumerated(EnumType.STRING)` → lưu enum dưới dạng string

---

## 6. Khi thêm tính năng mới, code đặt đâu?

### 6.1 Thêm endpoint mới

```
1. DTO (request/response)     → common/dto/ hoặc api-service/dto/
2. Controller method           → controller/PaymentController.java
3. Service method              → service/PaymentService.java
4. Repository query (nếu cần)  → repository/PaymentRepository.java
```

### 6.2 Thêm pipeline step

```
1. Tạo class implement PipelineStep → pipeline/steps/NewStep.java
2. @Component + set order()
3. Spring tự inject vào pipeline
4. Không cần sửa FraudDetectionPipeline!
```

### 6.3 Thêm Kafka consumer

```
1. Tạo class @Component          → kafka/consumer/NewConsumer.java
2. Dùng @KafkaListener
3. Inject dependencies (repository, kafkaTemplate)
```

### 6.4 Thêm config

```
1. Thêm property trong application.yml
2. Dùng @Value hoặc @ConfigurationProperties
```

---

## 7. Resources folder

```
api-service/src/main/resources/
├── application.yml           ← Main config
├── application-kafka.yml     ← Profile: kafka
├── application-rabbitmq.yml  ← Profile: rabbitmq
├── db/migration/             ← Flyway SQL migrations
│   ├── V1__create_tables.sql
│   └── V2__add_indexes.sql
└── logback-spring.xml        ← Logging config
```

---

## 8. Test folder

```
api-service/src/test/java/com/fraud/api/
├── controller/
│   └── PaymentControllerTest.java    ← @WebMvcTest
├── service/
│   └── PaymentServiceTest.java       ← @ExtendWith(MockitoExtension.class)
├── repository/
│   └── PaymentRepositoryIT.java      ← @DataJpaTest
└── integration/
    └── PaymentFlowIT.java            ← @SpringBootTest
```

---

## 9. Mapping file ↔ chức năng

| Bạn muốn | Sửa file nào |
|----------|--------------|
| Thêm REST endpoint | `controller/*.java` |
| Thêm business logic | `service/*.java` |
| Thêm DB query | `repository/*.java` |
| Thêm column DB | `domain/*.java` + migration SQL |
| Thêm pipeline step | `pipeline/steps/*.java` |
| Thêm validation | DTO + `@Valid` trong controller |
| Thêm config | `application.yml` + `@Value`/`@ConfigurationProperties` |
| Thêm Kafka consumer | `kafka/consumer/*.java` |
| Thêm exception | `exception/*.java` + `@ControllerAdvice` |

---

## 10. Kiểm tra hiểu bài

1. Khi request vào, thứ tự đi qua các layer là gì?
2. `@Transactional` đặt ở layer nào?
3. `PaymentRepository` là interface, ai implement nó?
4. Làm sao thêm 1 pipeline step mới mà không phải sửa `FraudDetectionPipeline`?
5. Module `common/` để làm gì?

### Đáp án

1. Controller → Service → Pipeline → Step → Repository → DB
2. Service layer (business logic)
3. Spring Data JPA auto-generate proxy implementation
4. Tạo class `@Component implements PipelineStep` — Spring tự inject vào List
5. Share code (DTOs, enums, events) giữa các modules

---

## 📚 Tiếp theo

→ [`05-request-lifecycle.md`](./05-request-lifecycle.md) — HTTP request đi qua Spring như thế nào
