# 10. Logging & Observability — Nhìn thấy hệ thống

## 🎯 Mục tiêu

- Viết log đúng cách (structured, với context)
- Hiểu 3 pillars: logs, metrics, traces
- Monitor production với Prometheus + Grafana
- Track distributed transactions với OpenTelemetry

---

## 1. Logging Best Practices

### 1.1 Sử dụng SLF4J + Logback

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PaymentService {
    // ❌ KHÔNG dùng System.out.println
    // ❌ KHÔNG dùng log4j trực tiếp
    
    // ✓ DÙNG SLF4J (facade)
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    
    // Hoặc với Lombok
    // @Slf4j  ← Tự tạo logger
}
```

### 1.2 Log levels — khi nào dùng gì?

```java
log.trace("Entering method with args: {}", args);  // Rất chi tiết, thường tắt
log.debug("Intermediate calculation: {}", value);   // Debug, dev only
log.info("Payment authorized: {}", paymentId);      // Sự kiện quan trọng
log.warn("Retry attempt {} of 3", attempt);         // Cảnh báo, có thể bỏ qua
log.error("Failed to process payment", exception);  // Lỗi cần xử lý
```

**Quy tắc:**

| Level | Production | Khi nào dùng |
|-------|------------|--------------|
| TRACE | OFF | Xem chi tiết nhất (entry/exit method) |
| DEBUG | OFF | Debug logic, values |
| INFO | ON | Business events (payment created, user login) |
| WARN | ON | Dấu hiệu bất thường (retry, fallback) |
| ERROR | ON | Lỗi cần action (exception, failure) |

### 1.3 Parameterized logging — RẤT QUAN TRỌNG

```java
// ❌ BAD: String concatenation
log.debug("Processing payment " + paymentId + " for merchant " + merchantId);
// → Lúc nào cũng concatenate (dù DEBUG OFF)

// ✓ GOOD: Placeholder
log.debug("Processing payment {} for merchant {}", paymentId, merchantId);
// → Chỉ format khi DEBUG ON
```

### 1.4 Log exception ĐÚNG CÁCH

```java
try {
    processPayment();
} catch (Exception e) {
    // ❌ BAD: Chỉ message, mất stack trace
    log.error("Failed: " + e.getMessage());
    
    // ❌ BAD: e.toString() không có stack
    log.error("Failed: {}", e.toString());
    
    // ✓ GOOD: Exception là THAM SỐ CUỐI (không dùng {})
    log.error("Failed to process payment", e);
    
    // ✓ GOOD với context
    log.error("Failed to process payment {} for {}", paymentId, merchantId, e);
    //                                                                        ↑
    //                                              Exception cuối, không {} tương ứng
}
```

### 1.5 Structured Logging

```java
// ❌ Unstructured
log.info("User john logged in from 192.168.1.1 at 2024-01-15");

// ✓ Structured với MDC
MDC.put("userId", "john");
MDC.put("ip", "192.168.1.1");
log.info("User logged in");

// Output (JSON format):
// {
//   "timestamp": "2024-01-15T10:30:00",
//   "level": "INFO",
//   "message": "User logged in",
//   "mdc": {
//     "userId": "john",
//     "ip": "192.168.1.1"
//   }
// }
```

### 1.6 Cấu hình Logback

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <!-- Console appender cho dev -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{36} - %msg%n
            </pattern>
        </encoder>
    </appender>
    
    <!-- File appender với rotation -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/app-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{ISO8601} [%thread] [%X{traceId:-}] %-5level %logger - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- JSON appender cho production (Elasticsearch) -->
    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/app.json</file>
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp/>
                <logLevel/>
                <mdc/>
                <message/>
                <stackTrace/>
            </providers>
        </encoder>
    </appender>
    
    <!-- Logger config -->
    <logger name="com.fraud" level="DEBUG"/>
    <logger name="org.hibernate.SQL" level="DEBUG"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

---

## 2. 3 Pillars of Observability

```
┌─────────────────────────────────────────────────────────────────────────┐
│                  THREE PILLARS OF OBSERVABILITY                        │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
    │    LOGS     │     │   METRICS   │     │   TRACES    │
    │             │     │             │     │             │
    │ Chi tiết    │     │ Thống kê    │     │ Theo dấu    │
    │ events      │     │ numbers     │     │ distributed │
    │             │     │             │     │ requests    │
    │             │     │             │     │             │
    │ What        │     │ How much    │     │ Where       │
    │ happened    │     │ & how fast  │     │ & how long  │
    └─────────────┘     └─────────────┘     └─────────────┘
         │                   │                   │
         ▼                   ▼                   ▼
    Loki/ELK           Prometheus          Jaeger/Zipkin
```

---

## 3. Metrics với Micrometer

### 3.1 Micrometer là gì?

**Micrometer = SLF4J cho metrics.** Một API → nhiều backend (Prometheus, Datadog, New Relic).

### 3.2 3 Loại metric chính

#### Counter — Đếm tăng dần

```java
@Component
public class PaymentMetrics {
    
    private final Counter authorizeCounter;
    
    public PaymentMetrics(MeterRegistry registry) {
        this.authorizeCounter = Counter.builder("payment.authorize.total")
            .description("Total authorize requests")
            .tag("type", "total")
            .register(registry);
    }
    
    public void incrementAuthorize() {
        authorizeCounter.increment();
    }
}
```

**Use case:** Đếm requests, errors, events.

#### Gauge — Current value

```java
private final AtomicInteger activeRequests = new AtomicInteger(0);

public PaymentMetrics(MeterRegistry registry) {
    Gauge.builder("payment.active.requests", activeRequests, AtomicInteger::get)
        .description("Currently active requests")
        .register(registry);
}
```

**Use case:** Queue size, memory, active connections.

#### Timer — Distribution

```java
private final Timer authorizeTimer;

public PaymentMetrics(MeterRegistry registry) {
    this.authorizeTimer = Timer.builder("payment.authorize.duration")
        .description("Authorize request duration")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry);
}

public void recordDuration(Runnable task) {
    authorizeTimer.record(task);
}

// Hoặc manual
Timer.Sample sample = Timer.start(registry);
// ... do work
sample.stop(authorizeTimer);
```

**Use case:** Latency, percentiles (p50, p95, p99).

### 3.3 Expose qua Prometheus

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    distribution:
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
```

```bash
# Xem metrics
curl http://localhost:8080/actuator/prometheus

# Output:
# payment_authorize_total 1234
# payment_authorize_duration_seconds_count 1234
# payment_authorize_duration_seconds_sum 456.78
# payment_authorize_duration_seconds{quantile="0.5"} 0.05
# payment_authorize_duration_seconds{quantile="0.99"} 0.2
```

### 3.4 Prometheus scrape config

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'fraud-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
    scrape_interval: 10s
```

### 3.5 Built-in metrics

```bash
# JVM memory
curl http://localhost:8080/actuator/metrics/jvm.memory.used

# HTTP requests
curl http://localhost:8080/actuator/metrics/http.server.requests

# Database connection pool
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active

# Kafka
curl http://localhost:8080/actuator/metrics/kafka.consumer.records.consumed.total
```

---

## 4. Grafana Dashboards

### 4.1 Setup

```bash
docker compose --profile observability up -d

# Grafana: http://localhost:3000 (admin/admin)
```

### 4.2 Key panels cho dự án này

**Panel 1: Throughput (RPS)**
```promql
rate(payment_authorize_total[1m])
```

**Panel 2: p99 Latency**
```promql
histogram_quantile(0.99, rate(payment_authorize_duration_seconds_bucket[1m]))
```

**Panel 3: Error Rate**
```promql
rate(payment_errors_total[1m]) / rate(payment_authorize_total[1m])
```

**Panel 4: JVM Heap**
```promql
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```

**Panel 5: Kafka Lag**
```promql
kafka_consumer_records_lag_max
```

### 4.3 Alerting

```yaml
# Prometheus alerting
groups:
  - name: fraud-api
    rules:
      - alert: HighErrorRate
        expr: rate(payment_errors_total[5m]) / rate(payment_authorize_total[5m]) > 0.05
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Error rate > 5%"
      
      - alert: HighLatency
        expr: histogram_quantile(0.99, rate(payment_duration_seconds_bucket[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "p99 latency > 1s"
```

---

## 5. Distributed Tracing với OpenTelemetry

### 5.1 Concepts

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         TRACE STRUCTURE                                │
└─────────────────────────────────────────────────────────────────────────┘

    Trace ID: abc-123
    ┌─────────────────────────────────────────────────────────────────────┐
    │                                                                     │
    │  Span 1: api-service HTTP POST /authorize (root)    [0-100ms]      │
    │  ├── Span 2: PaymentService.authorize()              [5-95ms]      │
    │  │    ├── Span 3: PaymentRepository.save()           [10-20ms]     │
    │  │    ├── Span 4: FraudPipeline.execute()            [25-80ms]     │
    │  │    │    ├── Span 5: IngestStep                    [26-30ms]     │
    │  │    │    ├── Span 6: FeatureStep                   [31-40ms]     │
    │  │    │    ├── Span 7: ModelStep                     [41-70ms]     │
    │  │    │    └── Span 8: RuleStep                      [71-79ms]     │
    │  │    └── Span 9: WebhookService.send()              [85-92ms]     │
    │  │                                                                  │
    └─────────────────────────────────────────────────────────────────────┘
```

### 5.2 Setup OpenTelemetry Agent

```bash
# Download agent
wget https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Chạy app với agent
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=fraud-api \
     -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
     -Dotel.traces.exporter=otlp \
     -jar app.jar
```

**Agent tự động:**
- Instrument Spring MVC, JDBC, Kafka, RabbitMQ
- Generate spans cho HTTP requests
- Propagate trace context qua services

### 5.3 Custom spans

```java
@Component
public class FraudDetectionPipeline {
    
    private final Tracer tracer;
    
    public PipelineContext execute(Payment payment) {
        Span span = tracer.spanBuilder("fraud-pipeline")
            .setAttribute("payment.id", payment.getId())
            .setAttribute("merchant.id", payment.getMerchantId())
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            PipelineContext ctx = executeSteps(payment);
            
            span.setAttribute("decision", ctx.getDecision().name());
            span.setAttribute("risk.score", ctx.getRiskScore());
            
            return ctx;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### 5.4 View trong Jaeger

```
http://localhost:16686
```

- Service: `fraud-api`
- Click Find Traces
- Click trace → xem span tree

---

## 6. Correlation: Logs + Metrics + Traces

### 6.1 TraceId trong logs

```java
// Thêm filter tự động set MDC từ trace
@Component
public class TraceIdFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, 
                                     FilterChain chain) throws ServletException, IOException {
        // OpenTelemetry tự generate trace ID
        String traceId = Span.current().getSpanContext().getTraceId();
        
        MDC.put("traceId", traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();
        }
    }
}
```

### 6.2 Từ Grafana → Jaeger

Trong Grafana dashboard, click latency spike → link đến Jaeger trace của request đó.

```json
{
  "dataSource": "Prometheus",
  "expr": "histogram_quantile(0.99, rate(http_duration_seconds_bucket[1m]))",
  "datasourceLinks": [
    {
      "title": "View trace",
      "url": "http://jaeger:16686/trace/${__field.labels.traceId}"
    }
  ]
}
```

### 6.3 Từ log → trace

```
ERROR 12345 --- [nio-8080-exec-1] [traceId=abc123] com.fraud.PaymentService - Error
```

Copy `abc123` → Jaeger → Search by Trace ID.

---

## 7. Production Logging Strategy

### 7.1 What to log

**LOG:**
- ✅ Service start/stop
- ✅ Business events (payment created, webhook sent)
- ✅ External API calls (request + response)
- ✅ Errors with context
- ✅ Performance milestones (slow queries)

**DON'T LOG:**
- ❌ Passwords, tokens, PII
- ❌ Mỗi request (overhead)
- ❌ Trong loops (log spam)
- ❌ Full request/response body (quá lớn)

### 7.2 Log sampling

```java
// Chỉ log 1% traces để giảm volume
if (ThreadLocalRandom.current().nextDouble() < 0.01) {
    log.debug("Detailed trace: {}", data);
}
```

### 7.3 Log aggregation

```
Application logs
     │
     ▼
Filebeat / Fluentd / Promtail
     │
     ▼
Elasticsearch / Loki
     │
     ▼
Kibana / Grafana Explore
```

---

## 8. Ví dụ: Track 1 request

### 8.1 Client request

```bash
curl -X POST http://localhost:8080/payments/authorize \
    -H "X-Request-ID: req-001" \
    -d '{"payment_id":"p-123", ...}'
```

### 8.2 App logs

```
10:30:15.100 [exec-1] [traceId=abc123] INFO  PaymentController - Received authorize request
10:30:15.110 [exec-1] [traceId=abc123] DEBUG PaymentService - Processing payment p-123
10:30:15.115 [exec-1] [traceId=abc123] DEBUG IngestStep - Validation passed
10:30:15.130 [exec-1] [traceId=abc123] DEBUG FeatureStep - Features extracted
10:30:15.165 [exec-1] [traceId=abc123] DEBUG ModelStep - Risk score: 0.35
10:30:15.175 [exec-1] [traceId=abc123] DEBUG RuleStep - Decision: ALLOW
10:30:15.185 [exec-1] [traceId=abc123] INFO  PaymentService - Payment p-123 authorized
10:30:15.190 [exec-1] [traceId=abc123] INFO  PaymentController - Returning response
```

### 8.3 Metrics recorded

```
payment_authorize_total{status="success"} = 1235
payment_authorize_duration_seconds{quantile="0.99"} = 0.15
payment_decision_total{decision="ALLOW"} = 950
```

### 8.4 Trace in Jaeger

```
fraud-api: POST /payments/authorize   [90ms]
├─ PaymentController.authorize         [85ms]
│  └─ PaymentService.authorize         [80ms]
│     ├─ PaymentRepository.findById    [10ms]
│     ├─ FraudPipeline.execute         [55ms]
│     │  ├─ IngestStep                 [5ms]
│     │  ├─ FeatureStep                [15ms]
│     │  ├─ ModelStep                  [30ms]
│     │  └─ RuleStep                   [5ms]
│     └─ PaymentRepository.save        [10ms]
```

---

## 9. Tools cheatsheet

| Tool | Mục đích | URL |
|------|----------|-----|
| Prometheus | Metrics storage | http://localhost:9090 |
| Grafana | Dashboards | http://localhost:3000 |
| Jaeger | Distributed tracing | http://localhost:16686 |
| Kibana | Log search | http://localhost:5601 |
| Spring Actuator | App management | http://localhost:8080/actuator |

---

## 10. Kiểm tra hiểu bài

1. Tại sao không dùng `System.out.println`?
2. Log exception đúng cách thế nào?
3. Counter, Gauge, Timer khác nhau thế nào?
4. TraceId để làm gì?
5. Khi nào dùng DEBUG vs INFO log level?

### Đáp án

1. Không có level, không có structured, không có rotation — dùng SLF4J
2. Exception là tham số cuối, KHÔNG có `{}` tương ứng: `log.error("msg", e)`
3. Counter: đếm tăng dần. Gauge: current value. Timer: distribution (percentiles)
4. Correlate logs, metrics, traces của 1 request xuyên qua nhiều services
5. DEBUG: dev info (turn off prod). INFO: business events (turn on prod)

---

## 🎉 Kết thúc Core Knowledge!

Bạn đã đọc xong 11 bài trong `core/`. Bây giờ bạn có thể:

- ✅ Hiểu Java từ JVM đến Spring Boot
- ✅ Debug mọi vấn đề trong dự án
- ✅ Thêm tính năng mới đúng layer
- ✅ Optimize performance

**Next steps:**
- Đọc `docs/java-concepts/` cho chuyên sâu
- Đọc `docs/variants/` để hiểu từng pattern
- Thực hành: break code → fix → hiểu sâu

**Nhớ:** Debug kỹ năng nhất là **đọc stack trace** và **hiểu flow dữ liệu**!
