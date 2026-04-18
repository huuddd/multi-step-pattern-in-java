# 02. Spring Boot Startup — Khởi động thế nào?

## 🎯 Mục tiêu

- Hiểu điều gì xảy ra từ `java -jar app.jar` đến khi app sẵn sàng nhận request
- Hiểu auto-configuration của Spring Boot
- Hiểu ApplicationContext, Bean lifecycle
- Debug được lỗi startup

---

## 1. Big Picture — Spring Boot Startup Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                 SPRING BOOT STARTUP FLOW                               │
└─────────────────────────────────────────────────────────────────────────┘

    1. java -jar app.jar
              │
              ▼
    2. JVM loads Application class
              │
              ▼
    3. main() calls SpringApplication.run()
              │
              ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │  Spring Boot Lifecycle                                          │
    │                                                                 │
    │  ┌──────────────────┐                                          │
    │  │ Load config       │  ← application.yml, env vars            │
    │  │ (Environment)     │                                          │
    │  └────────┬─────────┘                                          │
    │           │                                                     │
    │           ▼                                                     │
    │  ┌──────────────────┐                                          │
    │  │ Create           │  ← Container chứa beans                  │
    │  │ ApplicationContext│                                          │
    │  └────────┬─────────┘                                          │
    │           │                                                     │
    │           ▼                                                     │
    │  ┌──────────────────┐                                          │
    │  │ Component Scan   │  ← Tìm @Component, @Service, @Controller│
    │  └────────┬─────────┘                                          │
    │           │                                                     │
    │           ▼                                                     │
    │  ┌──────────────────┐                                          │
    │  │ Auto-Configuration│ ← Config từ starter dependencies        │
    │  └────────┬─────────┘                                          │
    │           │                                                     │
    │           ▼                                                     │
    │  ┌──────────────────┐                                          │
    │  │ Create Beans     │  ← Instantiate + inject dependencies    │
    │  └────────┬─────────┘                                          │
    │           │                                                     │
    │           ▼                                                     │
    │  ┌──────────────────┐                                          │
    │  │ Start Tomcat     │  ← Embedded server bind port 8080       │
    │  └────────┬─────────┘                                          │
    │           │                                                     │
    │           ▼                                                     │
    │  ┌──────────────────┐                                          │
    │  │ Ready!           │  ← App sẵn sàng nhận request             │
    │  └──────────────────┘                                          │
    └─────────────────────────────────────────────────────────────────┘
```

---

## 2. Entry Point — Application class

### 2.1 File Application.java

```java
@SpringBootApplication  // ← Magic annotation
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 2.2 @SpringBootApplication là gì?

**Thực chất là 3 annotations gộp lại:**

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration       // = @Configuration
@EnableAutoConfiguration       // Enable auto-config
@ComponentScan                 // Scan current package + sub-packages
public @interface SpringBootApplication { }
```

| Annotation | Vai trò |
|------------|---------|
| `@Configuration` | Class này định nghĩa beans |
| `@EnableAutoConfiguration` | Kích hoạt auto-config Spring Boot |
| `@ComponentScan` | Tự động scan `@Component`, `@Service`... |

---

## 3. Component Scan — Spring tìm Beans ở đâu?

### 3.1 Cách hoạt động

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      COMPONENT SCAN                                    │
└─────────────────────────────────────────────────────────────────────────┘

    Application.java ở package: c   om.fraud.api
                                         │
                                         ▼
    Spring scan package này + SUB-PACKAGES:
    
    com.fraud.api/
    ├── Application.java          ← Entry point
    ├── controller/
    │   └── PaymentController.java  @RestController ✓ SCAN
    ├── service/
    │   └── PaymentService.java     @Service ✓ SCAN
    ├── repository/
    │   └── PaymentRepository.java  Interface JPA ✓ SCAN
    └── pipeline/
        └── steps/
            └── IngestStep.java     @Component ✓ SCAN
```

### 3.2 Annotations để Spring nhận diện

```java
@Component       // Generic bean
@Service         // Business logic (= @Component)
@Repository      // Data access (= @Component)
@Controller      // Web controller (= @Component)
@RestController  // REST API (= @Controller + @ResponseBody)
@Configuration   // Config class (= @Component)
```

**Chúng đều là `@Component`**, chỉ khác về semantic (ý nghĩa).

### 3.3 Ví dụ trong dự án

```java
// PaymentController.java
@RestController
@RequestMapping("/payments")
public class PaymentController {
    
    private final PaymentService paymentService;
    
    // Constructor injection
    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }
    
    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@RequestBody AuthorizeRequest request) {
        return paymentService.authorize(request);
    }
}
```

**Spring tự động:**
1. Tạo instance `PaymentController`
2. Tìm `PaymentService` bean
3. Inject vào constructor
4. Register route `POST /payments/authorize`

---

## 4. Auto-Configuration — Magic của Spring Boot

### 4.1 Auto-Configuration là gì?

**Khi bạn thêm dependency, Spring Boot tự config giúp bạn.**

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // ↑ Thêm dependency này
}
```

**Spring Boot tự động:**
- ✅ Tạo embedded Tomcat
- ✅ Config DispatcherServlet (handle HTTP)
- ✅ Config Jackson (JSON serialization)
- ✅ Config MessageConverters
- ✅ Enable Spring MVC

### 4.2 Cách kiểm tra auto-config

```bash
# Chạy app với debug mode
./gradlew :api-service:bootRun --args='--debug'

# Output sẽ có:
# ============================
# CONDITIONS EVALUATION REPORT
# ============================
# 
# Positive matches:
# -----------------
#   DispatcherServletAutoConfiguration matched:
#     - @ConditionalOnClass found required class...
#   
# Negative matches:
# -----------------
#   KafkaAutoConfiguration did not match:
#     - @ConditionalOnClass did not find class KafkaTemplate
```

### 4.3 @Conditional — Cơ chế đằng sau

```java
@Configuration
@ConditionalOnClass(DataSource.class)  // Nếu có class DataSource
@ConditionalOnProperty("spring.datasource.url")  // Và có property
public class DataSourceAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean  // Nếu user chưa define bean này
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }
}
```

**Quy tắc:**
- Spring Boot cung cấp **default bean**
- Nếu bạn define bean tương tự → Spring dùng của bạn
- Nếu không → Spring dùng default

---

## 5. ApplicationContext — "Container" chứa Beans

### 5.1 ApplicationContext là gì?

**ApplicationContext = cái túi chứa tất cả beans của app.**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       APPLICATION CONTEXT                              │
└─────────────────────────────────────────────────────────────────────────┘

    ┌────────────────────────────────────────────────────────────────┐
    │                    ApplicationContext                          │
    │                                                                │
    │  Beans:                                                        │
    │  ┌──────────────────────┐  ┌──────────────────────┐          │
    │  │ PaymentController    │  │ PaymentService       │          │
    │  │ (depends on Service) │──│ (depends on Repo)    │          │
    │  └──────────────────────┘  └──────────┬───────────┘          │
    │                                        │                       │
    │  ┌──────────────────────┐              ▼                       │
    │  │ DataSource           │   ┌──────────────────────┐          │
    │  │ (DB connection pool) │   │ PaymentRepository    │          │
    │  └──────────┬───────────┘   │ (interface JPA)      │          │
    │             │               └──────────────────────┘          │
    │             ▼                                                   │
    │  ┌──────────────────────┐   ┌──────────────────────┐          │
    │  │ EntityManagerFactory │   │ TransactionManager   │          │
    │  └──────────────────────┘   └──────────────────────┘          │
    │                                                                │
    │  Infrastructure beans:                                        │
    │  ┌──────────────────────┐   ┌──────────────────────┐          │
    │  │ DispatcherServlet    │   │ Tomcat               │          │
    │  └──────────────────────┘   └──────────────────────┘          │
    └────────────────────────────────────────────────────────────────┘
```

### 5.2 Bean Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         BEAN LIFECYCLE                                 │
└─────────────────────────────────────────────────────────────────────────┘

    1. INSTANTIATE
       └─▶ new PaymentService()
       
    2. POPULATE PROPERTIES
       └─▶ Inject dependencies (constructor, setter, field)
       
    3. BEAN NAME AWARE
       └─▶ setBeanName("paymentService")
       
    4. CONTEXT AWARE
       └─▶ setApplicationContext(ctx)
       
    5. @PostConstruct / InitializingBean.afterPropertiesSet()
       └─▶ init() method
       
    6. READY TO USE ✓
       │
       │   (Bean được dùng trong suốt app lifecycle)
       │
       ▼
    7. @PreDestroy / DisposableBean.destroy()
       └─▶ cleanup() khi app shutdown
```

### 5.3 Ví dụ thực tế

```java
@Service
public class PaymentService {
    
    private final PaymentRepository repository;
    private final KafkaTemplate<String, PaymentEvent> kafka;
    
    // Constructor injection (RECOMMENDED)
    public PaymentService(
            PaymentRepository repository,
            KafkaTemplate<String, PaymentEvent> kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }
    
    @PostConstruct
    public void init() {
        log.info("PaymentService ready!");
        // Runs after dependencies injected
    }
    
    @PreDestroy
    public void cleanup() {
        log.info("PaymentService shutting down...");
        // Runs before app stops
    }
}
```

---

## 6. Configuration Loading

### 6.1 application.yml

```yaml
# src/main/resources/application.yml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fraud
    username: fraud
    password: fraud
  
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

logging:
  level:
    com.fraud: DEBUG
```

### 6.2 Override config

**Thứ tự ưu tiên (từ cao → thấp):**

```
1. Command line args:    --server.port=9090
2. System properties:    -Dserver.port=9090
3. Environment variables: SERVER_PORT=9090
4. application-{profile}.yml
5. application.yml
```

### 6.3 Profiles

```bash
# Chạy với profile 'kafka'
./gradlew bootRun --args='--spring.profiles.active=kafka'

# Spring load:
# - application.yml (base)
# - application-kafka.yml (override)
```

```yaml
# application-kafka.yml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: fraud-group
```

### 6.4 @Value và @ConfigurationProperties

```java
// Cách 1: @Value (simple)
@Component
public class RateLimiter {
    @Value("${rate-limit.permits-per-second:100}")
    private double permitsPerSecond;
}

// Cách 2: @ConfigurationProperties (structured)
@ConfigurationProperties(prefix = "rate-limit")
@Component
public class RateLimitProperties {
    private double permitsPerSecond = 100;
    private int burstSize = 200;
    
    // getters/setters
}
```

```yaml
rate-limit:
  permits-per-second: 500
  burst-size: 1000
```

---

## 7. Startup Order

### 7.1 Khi nào bean nào được tạo?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      BEAN CREATION ORDER                               │
└─────────────────────────────────────────────────────────────────────────┘

    1. Infrastructure beans
       - Environment
       - PropertySources
       - BeanFactoryPostProcessors
       
    2. Auto-configuration beans
       - DataSource
       - EntityManagerFactory
       - TransactionManager
       
    3. User-defined beans (@Configuration classes)
       - @Bean methods
       
    4. Component-scanned beans
       - @Service, @Repository, @Controller
       
    5. Servlet beans
       - DispatcherServlet
       - Filters
       
    6. Lifecycle callbacks
       - @PostConstruct
       - ApplicationRunner
       - CommandLineRunner
       
    7. Tomcat start
       - Bind port 8080
       - Ready to accept requests
```

### 7.2 @DependsOn — Force order

```java
@Component
@DependsOn("kafkaTemplate")  // Chờ kafkaTemplate ready trước
public class PaymentPublisher {
    // ...
}
```

### 7.3 ApplicationRunner — Chạy sau khi app ready

```java
@Component
public class StartupRunner implements ApplicationRunner {
    
    @Override
    public void run(ApplicationArguments args) {
        log.info("App is ready!");
        // Chạy task khởi tạo, warm-up cache, v.v.
    }
}
```

---

## 8. Startup Logs — Đọc như thế nào?

### 8.1 Ví dụ log startup

```
2024-01-15 10:30:00.123  INFO --- Starting Application using Java 21
2024-01-15 10:30:00.234  INFO --- Active profile: default
2024-01-15 10:30:01.456  INFO --- Bootstrapping Spring Data JPA repositories
2024-01-15 10:30:02.123  INFO --- HikariPool-1 - Starting...
2024-01-15 10:30:02.345  INFO --- HikariPool-1 - Start completed
2024-01-15 10:30:03.456  INFO --- Initialized JPA EntityManagerFactory
2024-01-15 10:30:03.789  INFO --- Tomcat initialized with port 8080
2024-01-15 10:30:04.123  INFO --- Started Application in 4.2 seconds
```

### 8.2 Phân tích từng dòng

| Dòng | Ý nghĩa |
|------|---------|
| `Starting Application` | Bắt đầu startup |
| `Active profile` | Profile đang dùng |
| `Bootstrapping Spring Data JPA` | Đang config JPA |
| `HikariPool-1 - Starting` | DB connection pool khởi động |
| `Initialized JPA EntityManagerFactory` | JPA sẵn sàng |
| `Tomcat initialized with port 8080` | Web server lên |
| `Started Application in 4.2 seconds` | App ready! |

---

## 9. Debug Startup Issues

### 9.1 App không start

**Case 1: Port đã dùng**

```
***************************
APPLICATION FAILED TO START
***************************

Description:
Web server failed to start. Port 8080 was already in use.

Action:
Identify and stop the process that's listening on port 8080
```

**Fix:**
```bash
# Windows: Tìm process dùng port 8080
netstat -ano | findstr :8080
taskkill /PID <PID> /F

# Hoặc đổi port
./gradlew bootRun --args='--server.port=9090'
```

**Case 2: Missing bean**

```
***************************
APPLICATION FAILED TO START
***************************

Description:
Parameter 0 of constructor in PaymentService required a bean of type 
'KafkaTemplate' that could not be found.

Action:
Consider defining a bean of type 'KafkaTemplate' in your configuration.
```

**Fix:**
- Check dependency đã thêm chưa
- Check `@SpringBootApplication` có scan đúng package không
- Check profile đúng chưa

### 9.2 Bật debug log

```yaml
# application.yml
logging:
  level:
    root: INFO
    org.springframework: DEBUG
    com.fraud: DEBUG
```

### 9.3 Actuator endpoints

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,beans,env,configprops
```

```bash
# Xem tất cả beans
curl http://localhost:8080/actuator/beans

# Xem config
curl http://localhost:8080/actuator/configprops

# Xem environment
curl http://localhost:8080/actuator/env
```

---

## 10. Kiểm tra hiểu bài

1. `@SpringBootApplication` tương đương với những annotation nào?
2. Component Scan tìm beans ở đâu?
3. Làm sao để override bean mà Spring Boot auto-configure?
4. Thứ tự ưu tiên config: `application.yml` vs environment variable vs command line?
5. Làm sao chạy code sau khi app ready?

### Đáp án

1. `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`
2. Trong package của `@SpringBootApplication` class và sub-packages
3. Define bean cùng loại — `@ConditionalOnMissingBean` sẽ skip default
4. Command line > env var > `application.yml`
5. Implement `ApplicationRunner` hoặc `CommandLineRunner`

---

## 📚 Tiếp theo

→ [`03-dependency-injection.md`](./03-dependency-injection.md) — Dependency Injection & IoC
