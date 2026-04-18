# 03. Dependency Injection & IoC

## 🎯 Mục tiêu

- Hiểu IoC (Inversion of Control) là gì, tại sao quan trọng
- Biết 3 cách inject dependency (constructor, setter, field)
- Hiểu bean scope (singleton vs prototype)
- Debug lỗi DI thường gặp

---

## 1. Vấn đề không có DI

### 1.1 Code tightly coupled

```java
public class PaymentService {
    private PaymentRepository repository;
    private KafkaTemplate kafka;
    
    public PaymentService() {
        // Service TỰ TẠO dependencies → tightly coupled
        this.repository = new PaymentRepository();
        this.kafka = new KafkaTemplate();
    }
}
```

**Vấn đề:**
- ❌ Không test được (không mock được dependencies)
- ❌ Thay đổi `PaymentRepository` → phải sửa `PaymentService`
- ❌ Không swap implementation (vd: MySQL → PostgreSQL)

### 1.2 IoC giải quyết

**Inversion of Control = Đảo ngược quyền kiểm soát.**

```java
public class PaymentService {
    private final PaymentRepository repository;  // Không tự tạo
    private final KafkaTemplate kafka;
    
    // Dependencies được INJECT từ bên ngoài
    public PaymentService(PaymentRepository repository, KafkaTemplate kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }
}
```

**Lợi ích:**
- ✅ Test dễ (mock dependencies)
- ✅ Loosely coupled
- ✅ Dễ thay đổi implementation

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       IOC - CONTROL FLIPPED                            │
└─────────────────────────────────────────────────────────────────────────┘

    KHÔNG CÓ IoC:                       CÓ IoC:
    ┌──────────────┐                    ┌──────────────┐
    │PaymentService│                    │PaymentService│
    │              │                    │              │
    │ new Repo()   │ ← Tự tạo           │ Repo repo    │ ← Nhận từ ngoài
    │ new Kafka()  │                    │ Kafka kafka  │
    └──────────────┘                    └──────▲───────┘
                                               │
                                        ┌──────┴───────┐
                                        │  Spring IoC  │
                                        │  Container   │
                                        └──────────────┘
                                        (Quản lý beans)
```

---

## 2. 3 Cách Inject Dependency

### 2.1 Constructor Injection (RECOMMENDED)

```java
@Service
public class PaymentService {
    
    private final PaymentRepository repository;  // final ✓
    private final KafkaTemplate kafka;
    
    // Spring auto-inject vào constructor
    public PaymentService(PaymentRepository repository, KafkaTemplate kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }
}
```

**Ưu điểm:**
- ✅ `final` field → immutable, thread-safe
- ✅ Không tạo object nếu thiếu dependency (fail fast)
- ✅ Dễ test (tạo mock dễ)
- ✅ Spring tự động inject từ Spring 4.3+ (không cần `@Autowired`)

### 2.2 Setter Injection

```java
@Service
public class PaymentService {
    
    private PaymentRepository repository;
    
    @Autowired
    public void setRepository(PaymentRepository repository) {
        this.repository = repository;
    }
}
```

**Khi nào dùng:**
- Optional dependency
- Circular dependency (hiếm khi)

### 2.3 Field Injection (NOT RECOMMENDED)

```java
@Service
public class PaymentService {
    
    @Autowired  // Inject trực tiếp vào field
    private PaymentRepository repository;
}
```

**Nhược điểm:**
- ❌ Không thể `final`
- ❌ Khó test (phải dùng reflection)
- ❌ Ẩn dependencies
- ❌ Dễ tạo circular dependency

**KHUYẾN NGHỊ:** Luôn dùng **Constructor Injection**.

---

## 3. @Autowired & Bean Resolution

### 3.1 Spring tìm bean như thế nào?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    BEAN RESOLUTION                                     │
└─────────────────────────────────────────────────────────────────────────┘

    Spring cần inject PaymentRepository
                    │
                    ▼
    1. Tìm bean có type = PaymentRepository
                    │
        ┌───────────┼───────────┐
        │           │           │
        ▼           ▼           ▼
    Found 1 bean  Found 0     Found >1
        │           │           │
        ▼           ▼           ▼
    ✅ Inject    ❌ Throw      Xem @Qualifier
                NoSuchBean    hoặc @Primary
                Exception
```

### 3.2 Nhiều implementations — @Qualifier

```java
// 2 implementations cùng interface
@Service("syncPaymentService")
public class SyncPaymentService implements PaymentService { }

@Service("asyncPaymentService")
public class AsyncPaymentService implements PaymentService { }

// Controller cần chọn 1
@RestController
public class PaymentController {
    
    private final PaymentService service;
    
    public PaymentController(
            @Qualifier("asyncPaymentService") PaymentService service) {
        this.service = service;
    }
}
```

### 3.3 @Primary — Default choice

```java
@Service
@Primary  // Mặc định inject bean này
public class DefaultPaymentService implements PaymentService { }

@Service
public class AlternativePaymentService implements PaymentService { }

// Không cần @Qualifier, Spring inject DefaultPaymentService
@Autowired
private PaymentService service;
```

### 3.4 @Profile — Theo môi trường

```java
@Service
@Profile("dev")
public class MockPaymentService implements PaymentService { }

@Service
@Profile("prod")
public class RealPaymentService implements PaymentService { }

// Chạy với --spring.profiles.active=dev → inject MockPaymentService
```

---

## 4. Bean Scopes

### 4.1 Singleton (default)

```java
@Service  // Default scope = singleton
public class PaymentService {
    // 1 instance duy nhất cho toàn app
    // Shared giữa tất cả requests
    // Phải thread-safe!
}
```

**Đặc điểm:**
- 1 instance per ApplicationContext
- Shared across requests
- **PHẢI THREAD-SAFE** (không dùng mutable state)

### 4.2 Prototype

```java
@Component
@Scope("prototype")
public class PipelineContext {
    // Tạo instance mới mỗi lần inject
}
```

**Khi nào dùng:**
- Stateful bean
- Mỗi request cần instance riêng

### 4.3 Request / Session (web only)

```java
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RequestContext {
    private String userId;
    // Instance riêng cho mỗi HTTP request
}
```

### 4.4 Ví dụ thực tế

```java
// ✅ DÚNG: Singleton + stateless
@Service
public class PaymentService {
    private final PaymentRepository repository;  // final = immutable
    
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        // Chỉ dùng local variables (method-scope)
        // Thread-safe
    }
}

// ❌ SAI: Singleton + mutable state
@Service
public class CounterService {
    private int counter = 0;  // Shared state!
    
    public void increment() {
        counter++;  // RACE CONDITION!
    }
}

// ✅ Fix: Dùng AtomicInteger
@Service
public class CounterService {
    private final AtomicInteger counter = new AtomicInteger(0);
    
    public void increment() {
        counter.incrementAndGet();  // Thread-safe
    }
}
```

---

## 5. @Bean vs @Component

### 5.1 @Component — Class tự đánh dấu

```java
@Component  // Class tự khai báo "tôi là bean"
public class MyService {
    // Spring tạo instance khi scan
}
```

**Khi nào dùng:**
- Code bạn viết
- Class đơn giản, Spring tự instantiate được

### 5.2 @Bean — Config class tạo bean

```java
@Configuration
public class KafkaConfig {
    
    @Bean  // Method này return 1 bean
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate(
            ProducerFactory<String, PaymentEvent> factory) {
        KafkaTemplate<String, PaymentEvent> template = new KafkaTemplate<>(factory);
        template.setDefaultTopic("risk.ingest");
        return template;
    }
    
    @Bean
    public ProducerFactory<String, PaymentEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }
}
```

**Khi nào dùng:**
- Third-party class (không thể sửa để thêm `@Component`)
- Cần config phức tạp trước khi tạo bean
- Conditional bean creation

### 5.3 So sánh

| Aspect | @Component | @Bean |
|--------|------------|-------|
| Đặt trên | Class | Method |
| Who creates | Spring | Bạn (trong @Bean method) |
| Customization | Ít (chỉ constructor) | Nhiều |
| Third-party class | ❌ | ✅ |
| Multiple instances | ❌ | ✅ (nhiều @Bean methods) |

---

## 6. Trong dự án này

### 6.1 PaymentController

```java
@RestController  // @Component (bean singleton)
@RequestMapping("/payments")
public class PaymentController {
    
    // Constructor injection (RECOMMENDED)
    private final PaymentService paymentService;
    
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
}
```

### 6.2 KafkaProducerConfig

```java
@Configuration  // Class chứa @Bean methods
public class KafkaProducerConfig {
    
    @Bean  // Spring tạo bean KafkaTemplate
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    @Bean  // Spring tạo bean ProducerFactory
    public ProducerFactory<String, PaymentEvent> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerConfig());
    }
}
```

### 6.3 PerMerchantRateLimiter

```java
@Component
public class PerMerchantRateLimiter {
    
    // Dependency injection
    private final MeterRegistry meterRegistry;
    
    @Value("${rate-limit.default-permits:100}")  // Inject config
    private double defaultPermits;
    
    public PerMerchantRateLimiter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
}
```

---

## 7. Circular Dependency

### 7.1 Vấn đề

```java
@Service
public class ServiceA {
    public ServiceA(ServiceB b) { }  // A cần B
}

@Service
public class ServiceB {
    public ServiceB(ServiceA a) { }  // B cần A
}

// Lỗi:
// The dependencies of some of the beans form a cycle:
// ┌─────┐
// |  serviceA
// ↑     ↓
// |  serviceB
// └─────┘
```

### 7.2 Cách fix

**Cách 1: Refactor — Tạo ServiceC để break cycle**

```java
@Service
public class ServiceC {
    // Chứa common logic mà A và B đều dùng
}

@Service
public class ServiceA {
    public ServiceA(ServiceC c) { }
}

@Service
public class ServiceB {
    public ServiceB(ServiceC c) { }
}
```

**Cách 2: Lazy Injection (workaround)**

```java
@Service
public class ServiceA {
    public ServiceA(@Lazy ServiceB b) {  // Inject proxy, tạo khi cần
        this.b = b;
    }
}
```

**Cách 3: @PostConstruct (workaround)**

```java
@Service
public class ServiceA {
    private ServiceB b;
    private final ApplicationContext ctx;
    
    public ServiceA(ApplicationContext ctx) {
        this.ctx = ctx;
    }
    
    @PostConstruct
    public void init() {
        this.b = ctx.getBean(ServiceB.class);
    }
}
```

---

## 8. Common Errors

### 8.1 NoSuchBeanDefinitionException

```
No qualifying bean of type 'com.fraud.api.service.PaymentService' available
```

**Nguyên nhân:**
- Class không có `@Component`/`@Service`
- Class ở package không được scan
- Profile không active

**Fix:**
```java
// 1. Thêm @Service
@Service
public class PaymentService { }

// 2. Check package (phải trong package của @SpringBootApplication)

// 3. Check profile
@Service
@Profile("prod")  // Chỉ active khi profile=prod
public class PaymentService { }
```

### 8.2 NoUniqueBeanDefinitionException

```
No qualifying bean of type 'PaymentService' available: 
expected single matching bean but found 2: sync, async
```

**Fix:**
```java
// Dùng @Qualifier
public PaymentController(@Qualifier("sync") PaymentService service) { }

// Hoặc @Primary
@Service
@Primary
public class SyncPaymentService implements PaymentService { }
```

### 8.3 UnsatisfiedDependencyException

```
Error creating bean with name 'paymentController': 
Unsatisfied dependency expressed through constructor parameter 0
```

**Debug:**
```bash
# Xem stack trace đầy đủ
./gradlew bootRun --stacktrace

# Tìm root cause (thường là NoSuchBean ở sâu hơn)
```

---

## 9. Testing với DI

### 9.1 Unit Test — Mock dependencies

```java
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    
    @Mock
    private PaymentRepository repository;
    
    @Mock
    private KafkaTemplate<String, PaymentEvent> kafka;
    
    @InjectMocks
    private PaymentService service;  // Tự inject mocks
    
    @Test
    void shouldAuthorizePayment() {
        // Given
        when(repository.save(any())).thenReturn(new Payment());
        
        // When
        AuthorizeResponse response = service.authorize(request);
        
        // Then
        verify(repository).save(any());
        verify(kafka).send(anyString(), any());
    }
}
```

### 9.2 Integration Test — @SpringBootTest

```java
@SpringBootTest
class PaymentServiceIT {
    
    @Autowired
    private PaymentService service;  // Real bean from context
    
    @MockBean
    private KafkaTemplate<String, PaymentEvent> kafka;  // Mock specific bean
    
    @Test
    void shouldAuthorizeRealPayment() {
        // Dùng real PaymentRepository, mock KafkaTemplate
    }
}
```

---

## 10. Best Practices

### 10.1 DO ✅

```java
// ✅ Constructor injection
@Service
public class MyService {
    private final Dep dep;
    
    public MyService(Dep dep) {
        this.dep = dep;
    }
}

// ✅ Stateless singleton
@Service
public class MyService {
    public String process(String input) {
        return input.toUpperCase();  // Không có state
    }
}

// ✅ Interface + implementation
public interface PaymentService { }

@Service
public class DefaultPaymentService implements PaymentService { }
```

### 10.2 DON'T ❌

```java
// ❌ Field injection
@Service
public class MyService {
    @Autowired
    private Dep dep;  // Khó test, không final
}

// ❌ Stateful singleton
@Service
public class MyService {
    private int counter = 0;  // Race condition!
}

// ❌ new object trong service
@Service
public class MyService {
    public void method() {
        Dep dep = new Dep();  // Bypass Spring!
    }
}
```

---

## 11. Kiểm tra hiểu bài

1. Tại sao constructor injection tốt hơn field injection?
2. Scope mặc định của `@Service` là gì? Điều đó có nghĩa là gì?
3. Khi nào dùng `@Bean` thay vì `@Component`?
4. Làm sao fix circular dependency?
5. Singleton bean có thread-safe không?

### Đáp án

1. Có `final` field (immutable), dễ test, fail fast nếu thiếu dep
2. Singleton — 1 instance cho cả app, phải thread-safe
3. Khi bean là third-party class hoặc cần config phức tạp
4. Refactor để break cycle (tạo service trung gian)
5. KHÔNG tự động — phải tự code thread-safe (dùng final, atomic, immutable state)

---

## 📚 Tiếp theo

→ [`04-project-structure.md`](./04-project-structure.md) — Walkthrough dự án này
