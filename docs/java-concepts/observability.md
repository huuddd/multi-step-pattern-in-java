# Observability — Lý thuyết chuyên sâu

## Chương 1: Ba trụ cột của Observability

### 1.1 Định nghĩa

**Observability** là khả năng hiểu trạng thái bên trong của hệ thống dựa trên output bên ngoài (logs, metrics, traces).

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    THREE PILLARS OF OBSERVABILITY                      │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
    │    LOGS     │      │   METRICS   │      │   TRACES    │
    │             │      │             │      │             │
    │  What       │      │  How much   │      │  Where      │
    │  happened   │      │  & how fast │      │  & how long │
    │             │      │             │      │             │
    │  Discrete   │      │  Aggregated │      │  Distributed│
    │  events     │      │  numbers    │      │  context    │
    └─────────────┘      └─────────────┘      └─────────────┘
         │                    │                    │
         │                    │                    │
         ▼                    ▼                    ▼
    ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
    │  ELK Stack  │      │ Prometheus  │      │   Jaeger    │
    │  Loki       │      │ Grafana     │      │   Zipkin    │
    │  Splunk     │      │ Datadog     │      │   Tempo     │
    └─────────────┘      └─────────────┘      └─────────────┘
```

### 1.2 Logs

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              LOGS                                       │
└─────────────────────────────────────────────────────────────────────────┘

    Đặc điểm:
    - Discrete events với timestamp
    - Human-readable hoặc structured (JSON)
    - High cardinality (mỗi request có log riêng)
    
    Ví dụ:
    {
      "timestamp": "2024-01-15T10:30:00.123Z",
      "level": "INFO",
      "service": "fraud-api",
      "trace_id": "abc123",
      "span_id": "def456",
      "payment_id": "p-789",
      "message": "Payment authorized",
      "decision": "ALLOW",
      "latency_ms": 45
    }
    
    Use cases:
    - Debugging specific requests
    - Audit trail
    - Error investigation
```

### 1.3 Metrics

```
┌─────────────────────────────────────────────────────────────────────────┐
│                             METRICS                                     │
└─────────────────────────────────────────────────────────────────────────┘

    Types:
    
    1. COUNTER (monotonically increasing)
       ┌────────────────────────────────────────────────────────────────┐
       │  requests_total{status="200"} = 1000                          │
       │  requests_total{status="500"} = 5                             │
       │                                                                │
       │  Use: Count events (requests, errors, messages)               │
       └────────────────────────────────────────────────────────────────┘
    
    2. GAUGE (can go up or down)
       ┌────────────────────────────────────────────────────────────────┐
       │  queue_depth{queue="risk.ingest"} = 150                       │
       │  active_connections = 45                                       │
       │                                                                │
       │  Use: Current state (queue size, memory, connections)         │
       └────────────────────────────────────────────────────────────────┘
    
    3. HISTOGRAM (distribution)
       ┌────────────────────────────────────────────────────────────────┐
       │  request_duration_seconds_bucket{le="0.1"} = 900              │
       │  request_duration_seconds_bucket{le="0.5"} = 980              │
       │  request_duration_seconds_bucket{le="1.0"} = 995              │
       │                                                                │
       │  Use: Latency percentiles (p50, p95, p99)                     │
       └────────────────────────────────────────────────────────────────┘
```

### 1.4 Traces

```
┌─────────────────────────────────────────────────────────────────────────┐
│                             TRACES                                      │
└─────────────────────────────────────────────────────────────────────────┘

    Trace = collection of spans representing a request flow
    
    Trace ID: abc-123
    ┌─────────────────────────────────────────────────────────────────────┐
    │                                                                     │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │ Span: api-service (root)                          [0-100ms] │   │
    │  │ ├── Span: validate-request                        [5-10ms]  │   │
    │  │ ├── Span: kafka-produce                           [10-15ms] │   │
    │  │ └── Span: wait-response                           [15-95ms] │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                     │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │ Span: ingest-consumer                             [20-30ms] │   │
    │  │ └── Span: kafka-produce                           [25-28ms] │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                     │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │ Span: feature-consumer                            [35-50ms] │   │
    │  │ ├── Span: extract-features                        [36-45ms] │   │
    │  │ └── Span: kafka-produce                           [46-49ms] │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                     │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │ Span: model-consumer                              [55-80ms] │   │
    │  │ └── Span: calculate-risk-score                    [56-78ms] │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                     │
    │  ┌─────────────────────────────────────────────────────────────┐   │
    │  │ Span: rule-consumer                               [85-95ms] │   │
    │  │ ├── Span: apply-rules                             [86-90ms] │   │
    │  │ ├── Span: persist-decision                        [90-93ms] │   │
    │  │ └── Span: send-webhook                            [93-95ms] │   │
    │  └─────────────────────────────────────────────────────────────┘   │
    │                                                                     │
    └─────────────────────────────────────────────────────────────────────┘
    
    Use cases:
    - Find bottlenecks
    - Understand request flow
    - Debug distributed systems
```

---

## Chương 2: OpenTelemetry

### 2.1 OpenTelemetry là gì?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         OPENTELEMETRY                                   │
└─────────────────────────────────────────────────────────────────────────┘

    OpenTelemetry = OpenTracing + OpenCensus (merged)
    
    Components:
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐        │
    │  │    API      │    │    SDK      │    │  Exporter   │        │
    │  │             │    │             │    │             │        │
    │  │  Interface  │───▶│  Implement  │───▶│  Send to    │        │
    │  │  to create  │    │  sampling,  │    │  backend    │        │
    │  │  spans      │    │  batching   │    │  (Jaeger)   │        │
    │  └─────────────┘    └─────────────┘    └─────────────┘        │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
    
    Supported backends:
    - Jaeger, Zipkin (traces)
    - Prometheus (metrics)
    - Loki, Elasticsearch (logs)
    - Commercial: Datadog, New Relic, Splunk
```

### 2.2 Java Agent (Zero-code instrumentation)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    JAVA AGENT AUTO-INSTRUMENTATION                     │
└─────────────────────────────────────────────────────────────────────────┘

    Cách hoạt động:
    
    1. Attach agent khi start JVM:
       java -javaagent:opentelemetry-javaagent.jar -jar app.jar
    
    2. Agent tự động instrument:
       - Spring MVC/WebFlux (HTTP requests)
       - JDBC (database queries)
       - Kafka (producer/consumer)
       - RabbitMQ (publisher/consumer)
       - Redis, MongoDB, gRPC, ...
    
    3. Không cần sửa code!
    
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  Your Code                    Agent Bytecode Manipulation      │
    │  ┌─────────────────┐          ┌─────────────────────────────┐  │
    │  │                 │          │ // Injected by agent        │  │
    │  │ @GetMapping     │   ───▶   │ Span span = tracer.start(); │  │
    │  │ public Response │          │ try {                       │  │
    │  │ authorize() {   │          │   // Your code              │  │
    │  │   ...           │          │   authorize();              │  │
    │  │ }               │          │ } finally {                 │  │
    │  │                 │          │   span.end();               │  │
    │  └─────────────────┘          │ }                           │  │
    │                               └─────────────────────────────┘  │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

### 2.3 Custom Spans

```java
// Thêm custom span cho business logic
@Component
public class FraudDetectionPipeline {
    
    private final Tracer tracer;
    
    public PipelineContext execute(Payment payment) {
        Span span = tracer.spanBuilder("fraud-pipeline")
                .setAttribute("payment_id", payment.getPaymentId())
                .setAttribute("merchant_id", payment.getMerchantId())
                .setAttribute("amount", payment.getAmount())
                .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Execute pipeline steps
            PipelineContext ctx = executeSteps(payment);
            
            // Add result attributes
            span.setAttribute("decision", ctx.getDecision().name());
            span.setAttribute("risk_score", ctx.getRiskScore().doubleValue());
            
            return ctx;
            
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
            
        } finally {
            span.end();
        }
    }
}
```

---

## Chương 3: Metrics với Micrometer

### 3.1 Micrometer + Prometheus

```java
@Configuration
public class MetricsConfig {
    
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
                .commonTags("application", "fraud-api")
                .commonTags("environment", "production");
    }
}

@Component
public class PipelineMetrics {
    
    private final Counter authorizeCounter;
    private final Timer pipelineTimer;
    private final DistributionSummary riskScoreDistribution;
    
    public PipelineMetrics(MeterRegistry registry) {
        this.authorizeCounter = Counter.builder("pipeline.authorize.total")
                .description("Total authorization requests")
                .register(registry);
        
        this.pipelineTimer = Timer.builder("pipeline.duration")
                .description("Pipeline execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        
        this.riskScoreDistribution = DistributionSummary.builder("pipeline.risk_score")
                .description("Risk score distribution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
```

### 3.2 Key Metrics cho Fraud Detection

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    KEY METRICS TO MONITOR                               │
└─────────────────────────────────────────────────────────────────────────┘

    1. THROUGHPUT
       - pipeline.authorize.total (Counter)
       - Rate: requests per second
    
    2. LATENCY
       - pipeline.duration (Timer)
       - Percentiles: p50, p95, p99
    
    3. ERROR RATE
       - pipeline.errors.total (Counter)
       - Ratio: errors / total
    
    4. QUEUE DEPTH (for async variants)
       - kafka.consumer.lag (Gauge)
       - rabbitmq.queue.depth (Gauge)
    
    5. DECISION BREAKDOWN
       - pipeline.decision{decision="ALLOW"} (Counter)
       - pipeline.decision{decision="REVIEW"} (Counter)
       - pipeline.decision{decision="BLOCK"} (Counter)
    
    6. RESOURCE UTILIZATION
       - jvm.memory.used (Gauge)
       - jvm.threads.live (Gauge)
       - process.cpu.usage (Gauge)
```

---

## Chương 4: Grafana Dashboards

### 4.1 Dashboard Layout

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    FRAUD DETECTION DASHBOARD                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐        │
│  │  Throughput     │  │  Error Rate     │  │  p99 Latency    │        │
│  │  2,500 RPS      │  │  0.02%          │  │  120ms          │        │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘        │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  Latency Over Time                                               │   │
│  │  ┌───────────────────────────────────────────────────────────┐  │   │
│  │  │     p99 ─────                                              │  │   │
│  │  │     p95 ─────                                              │  │   │
│  │  │     p50 ─────                                              │  │   │
│  │  │  ▲                                                         │  │   │
│  │  │  │    ╱╲                                                   │  │   │
│  │  │  │   ╱  ╲    ╱╲                                           │  │   │
│  │  │  │  ╱    ╲  ╱  ╲                                          │  │   │
│  │  │  └──────────────────────────────────────────────▶ time    │  │   │
│  │  └───────────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌──────────────────────────┐  ┌──────────────────────────────────┐   │
│  │  Decision Breakdown      │  │  Queue Depth                      │   │
│  │  ┌───────────────────┐   │  │  ┌────────────────────────────┐  │   │
│  │  │ ALLOW  ████████ 75%│   │  │  │ risk.ingest    ███ 150    │  │   │
│  │  │ REVIEW ██ 15%      │   │  │  │ risk.feature   ██ 80      │  │   │
│  │  │ BLOCK  █ 10%       │   │  │  │ risk.model     █ 50       │  │   │
│  │  └───────────────────┘   │  │  │ risk.rule      █ 30       │  │   │
│  └──────────────────────────┘  │  └────────────────────────────┘  │   │
│                                 └──────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Tham khảo

- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [OpenTelemetry Java Agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Grafana Dashboards](https://grafana.com/docs/grafana/latest/dashboards/)
