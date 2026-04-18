# 05. Request Lifecycle — HTTP Request đi qua Spring như thế nào?

## 🎯 Mục tiêu

- Hiểu chi tiết từng bước xử lý HTTP request
- Biết Tomcat, DispatcherServlet, Filters hoạt động ra sao
- Debug được khi request không đi tới controller

---

## 1. Big Picture

```
┌─────────────────────────────────────────────────────────────────────────┐
│              HTTP REQUEST LIFECYCLE IN SPRING BOOT                     │
└─────────────────────────────────────────────────────────────────────────┘

    Client (Browser / curl / Postman)
          │
          │ HTTP Request
          ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  1. TCP/IP → Tomcat (port 8080)                                │
    │     - Accept TCP connection                                    │
    │     - Parse HTTP request                                       │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  2. Tomcat assigns Thread from pool                            │
    │     - http-nio-8080-exec-1 (200 threads by default)            │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  3. Servlet Filter Chain                                       │
    │     - Security (Spring Security)                               │
    │     - CORS                                                     │
    │     - Logging                                                  │
    │     - Trace ID generation                                      │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  4. DispatcherServlet (Front Controller)                       │
    │     - Find handler (controller method) from URL                │
    │     - Extract PathVariables, RequestParams, RequestBody        │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  5. HandlerInterceptor                                         │
    │     - preHandle() → Before controller                          │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  6. Argument Resolution                                        │
    │     - @PathVariable → Extract from URL                         │
    │     - @RequestParam → Extract from query string                │
    │     - @RequestBody → Deserialize JSON → Object                 │
    │     - @Valid → Validation                                      │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  7. Controller Method Invocation                               │
    │     - paymentController.authorize(request)                     │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  8. Service Layer                                              │
    │     - Business logic                                           │
    │     - DB operations (JPA)                                      │
    │     - External calls                                           │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  9. Return value → HTTP Response                               │
    │     - HttpMessageConverter serializes to JSON                  │
    │     - @ResponseStatus sets status code                         │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  10. HandlerInterceptor.postHandle()                           │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  11. Filter Chain (reverse order)                              │
    │     - Logging response                                         │
    │     - Metrics                                                  │
    └─────────────────────────┬──────────────────────────────────────┘
                              │
                              ▼
    ┌────────────────────────────────────────────────────────────────┐
    │  12. Tomcat sends response to client                           │
    │     - Serialize HTTP response                                  │
    │     - Send over TCP                                            │
    │     - Release thread back to pool                              │
    └────────────────────────────────────────────────────────────────┘
```

---

## 2. Chi tiết từng bước

### 2.1 Tomcat — Embedded Web Server

**Spring Boot mặc định dùng embedded Tomcat.**

```yaml
# application.yml
server:
  port: 8080
  tomcat:
    threads:
      max: 200          # Max thread count
      min-spare: 10     # Min idle threads
    max-connections: 8192
    accept-count: 100   # Queue size khi hết thread
    connection-timeout: 20000
```

**Cách Tomcat xử lý request:**

```
┌────────────────────────────────────────────────────────────────┐
│                      TOMCAT THREAD MODEL                       │
└────────────────────────────────────────────────────────────────┘

    Incoming requests
         │
         ▼
    ┌─────────────────┐
    │ Acceptor Thread │ (1 thread)
    │ - Accept TCP    │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────┐
    │  Poller Thread  │ (NIO)
    │ - Register sock │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────────────────────────────────┐
    │         Worker Thread Pool                  │
    │  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐          │
    │  │ T1  │ │ T2  │ │ T3  │ │ ... │  (200)   │
    │  └─────┘ └─────┘ └─────┘ └─────┘          │
    └─────────────────────────────────────────────┘
             │
             ▼
    Process request, call DispatcherServlet
```

**Quan trọng:** Mỗi request = 1 thread. Khi nào nên tăng thread count?
- I/O bound (DB, HTTP calls) → tăng threads (200-500)
- CPU bound → threads = số core CPU

### 2.2 Servlet Filter

**Filter = interceptor cho tất cả HTTP requests.**

```java
@Component
@Order(1)  // Thứ tự chạy
public class TraceIdFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        
        MDC.put("traceId", traceId);  // Thêm vào log context
        try {
            chain.doFilter(request, response);  // Pass to next filter
        } finally {
            MDC.clear();
        }
    }
}
```

**Chain of filters:**

```
Request → Filter1 → Filter2 → Filter3 → DispatcherServlet
                                           │
Response ← Filter1 ← Filter2 ← Filter3 ← Controller
```

### 2.3 DispatcherServlet — Front Controller

**DispatcherServlet = trung tâm điều phối tất cả HTTP requests.**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       DISPATCHER SERVLET                               │
└─────────────────────────────────────────────────────────────────────────┘

    Incoming Request
          │
          ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │  1. HandlerMapping                                              │
    │     URL "/payments/authorize" → PaymentController.authorize()  │
    └─────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │  2. HandlerAdapter                                              │
    │     - Invoke controller method                                  │
    │     - Resolve arguments                                         │
    └─────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │  3. HandlerInterceptor.preHandle()                              │
    └─────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │  4. Invoke Controller method                                    │
    └─────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │  5. ViewResolver / MessageConverter                             │
    │     - @RestController → JSON serialization (Jackson)            │
    │     - @Controller → Render view (Thymeleaf, etc.)               │
    └─────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
                          Response to client
```

### 2.4 Argument Resolution — Spring biến HTTP thành Java objects

```java
@PostMapping("/payments/{id}/review")
public ResponseEntity<?> review(
        @PathVariable String id,              // ← From URL
        @RequestParam(required = false) String reason,  // ← From query string
        @RequestBody ReviewRequest request,   // ← From JSON body
        @RequestHeader("Authorization") String auth,   // ← From header
        HttpServletRequest httpRequest        // ← Raw request
) {
    // ...
}
```

**Request:**
```http
POST /payments/p-123/review?reason=suspicious HTTP/1.1
Authorization: Bearer token
Content-Type: application/json

{
    "decision": "BLOCK",
    "notes": "Fraud detected"
}
```

**Spring resolve:**
- `id` = "p-123" (from `{id}` in URL)
- `reason` = "suspicious" (from `?reason=`)
- `request` = `ReviewRequest{decision=BLOCK, notes="..."}` (from JSON body)
- `auth` = "Bearer token" (from header)

### 2.5 @RequestBody Deserialization

**JSON → Java object bằng Jackson.**

```java
// Input JSON:
// {
//   "payment_id": "p-123",
//   "merchant_id": "m-001",
//   "amount": 500000
// }

// Java class:
public record AuthorizeRequest(
    @JsonProperty("payment_id") String paymentId,
    @JsonProperty("merchant_id") String merchantId,
    Long amount
) {}

// Spring automatically:
// 1. Read HTTP body
// 2. Jackson.readValue(body, AuthorizeRequest.class)
// 3. Pass object to controller
```

**Custom config Jackson:**

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE  # Auto map snake_case ↔ camelCase
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false
```

### 2.6 Validation — @Valid

```java
public record AuthorizeRequest(
    @NotBlank String paymentId,
    @NotBlank String merchantId,
    @Positive Long amount,
    @Pattern(regexp = "[A-Z]{3}") String currency
) {}

@PostMapping("/authorize")
public ResponseEntity<?> authorize(
        @Valid @RequestBody AuthorizeRequest request) {
    // ...
}
```

**Nếu validation fail:**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(err -> 
            errors.put(err.getField(), err.getDefaultMessage())
        );
        return ResponseEntity.badRequest().body(errors);
    }
}
```

### 2.7 Response Serialization

```java
@GetMapping("/{id}")
public Payment getPayment(@PathVariable String id) {
    return paymentRepository.findById(id).orElseThrow();
}

// Spring automatically:
// 1. Jackson.writeValueAsString(payment)
// 2. Set Content-Type: application/json
// 3. Write to HTTP response body
```

---

## 3. Exception Handling

### 3.1 @ControllerAdvice — Global exception handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", e.getMessage()));
    }
    
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IdempotencyConflictException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", e.getMessage()));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "Something went wrong"));
    }
}
```

### 3.2 Exception flow

```
Controller throws Exception
          │
          ▼
    DispatcherServlet catches
          │
          ▼
    HandlerExceptionResolver
          │
          ▼
    @ControllerAdvice matches @ExceptionHandler
          │
          ▼
    Convert to ResponseEntity
          │
          ▼
    Return to client
```

---

## 4. Thread Context — MDC, ThreadLocal

### 4.1 MDC (Mapped Diagnostic Context)

**Dùng để attach context (traceId, userId) vào log.**

```java
// Filter: Set MDC
MDC.put("traceId", UUID.randomUUID().toString());
MDC.put("userId", extractUserId(request));

// Service: Log tự động include MDC
log.info("Processing payment");
// Output: 2024-01-15 [traceId=abc123] [userId=u-1] Processing payment
```

### 4.2 Cấu hình logback

```xml
<!-- logback-spring.xml -->
<pattern>
    %d{HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger - %msg%n
</pattern>
```

### 4.3 Propagate MDC qua threads

```java
// ⚠️ MDC KHÔNG TỰ ĐỘNG propagate qua @Async/ExecutorService

// ❌ Sai
@Async
public void asyncMethod() {
    log.info("traceId lost!");  // MDC rỗng
}

// ✅ Đúng — Capture MDC trước khi submit
public void process() {
    Map<String, String> contextMap = MDC.getCopyOfContextMap();
    
    executor.submit(() -> {
        MDC.setContextMap(contextMap);
        try {
            // Do work
        } finally {
            MDC.clear();
        }
    });
}
```

---

## 5. Debug Request Lifecycle

### 5.1 Bật Spring debug logs

```yaml
logging:
  level:
    org.springframework.web: DEBUG
    org.springframework.web.servlet.DispatcherServlet: TRACE
```

### 5.2 Log sẽ hiển thị

```
DEBUG DispatcherServlet : POST "/payments/authorize"
DEBUG RequestMappingHandlerMapping : Mapped to PaymentController#authorize()
DEBUG RequestResponseBodyMethodProcessor : Read "application/json" to [AuthorizeRequest...]
DEBUG PaymentController : Authorizing payment p-123
DEBUG RequestResponseBodyMethodProcessor : Writing [AuthorizeResponse...]
DEBUG DispatcherServlet : Completed 200 OK
```

### 5.3 Actuator — Xem mappings

```bash
# Xem tất cả URL mappings
curl http://localhost:8080/actuator/mappings | jq

# Output:
# "/payments/authorize": {
#   "method": "POST",
#   "controller": "PaymentController",
#   "method": "authorize"
# }
```

---

## 6. Common Issues

### 6.1 404 Not Found

**Nguyên nhân:**
- URL sai
- Controller không được scan
- `@RequestMapping` path không match

**Debug:**
```bash
# Xem tất cả endpoints
curl http://localhost:8080/actuator/mappings

# Check log startup:
# Mapped "/payments/authorize" onto method...
```

### 6.2 400 Bad Request

**Nguyên nhân:**
- JSON body không valid
- `@Valid` fail
- Missing required field

**Debug:**
```yaml
# Bật debug để xem JSON parsing error
logging:
  level:
    org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor: DEBUG
```

### 6.3 500 Internal Server Error

**Nguyên nhân:**
- Exception không handle
- NullPointerException
- Database error

**Debug:**
```java
// Bật full stack trace
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception e) {
        log.error("Error", e);  // Log full stack
        return ResponseEntity.status(500).body(e.getMessage());
    }
}
```

### 6.4 Thread pool exhausted

```
WARN: org.apache.tomcat.util.threads.ThreadPoolExecutor: 
[http-nio-8080-exec-N] is in queue state
```

**Fix:**
```yaml
server:
  tomcat:
    threads:
      max: 500  # Tăng lên
```

---

## 7. Performance Tips

### 7.1 Keep controllers thin

```java
// ❌ DON'T — business logic in controller
@PostMapping("/authorize")
public Response authorize(@RequestBody Request req) {
    Payment p = new Payment();
    p.setId(req.id());
    // ... 100 lines of business logic
    paymentRepo.save(p);
    return new Response(p);
}

// ✅ DO — delegate to service
@PostMapping("/authorize")
public Response authorize(@RequestBody Request req) {
    return paymentService.authorize(req);
}
```

### 7.2 Use async for slow operations

```java
// Sync — blocks thread
@PostMapping("/notify")
public Response notify(@RequestBody Request req) {
    webhookService.send(req);  // 5 giây → thread bị block 5s
    return ok();
}

// Async — returns immediately
@PostMapping("/notify")
public Response notify(@RequestBody Request req) {
    CompletableFuture.runAsync(() -> webhookService.send(req));
    return ok();
}
```

### 7.3 Pagination cho list endpoints

```java
@GetMapping("/payments")
public Page<Payment> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    return paymentRepo.findAll(PageRequest.of(page, size));
}
```

---

## 8. Kiểm tra hiểu bài

1. Tại sao mỗi HTTP request = 1 thread?
2. Filter và Interceptor khác nhau thế nào?
3. Spring biến JSON body thành Java object bằng cách nào?
4. `@ControllerAdvice` để làm gì?
5. MDC có tự propagate qua `@Async` không?

### Đáp án

1. Servlet model: mỗi request blocking, cần thread riêng. (Virtual threads thay đổi điều này)
2. Filter: Servlet-level, run trước DispatcherServlet. Interceptor: Spring MVC-level, có context của handler
3. `HttpMessageConverter` (Jackson) — deserialize JSON → Java
4. Global exception handler cho tất cả controllers
5. KHÔNG — phải manually capture và propagate

---

## 📚 Tiếp theo

→ [`06-jpa-hibernate.md`](./06-jpa-hibernate.md) — JPA, Hibernate, transactions
