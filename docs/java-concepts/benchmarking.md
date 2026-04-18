# Java Concept: Benchmarking với Gatling

## 1. Tổng quan

**Benchmarking** là quá trình đo lường hiệu năng hệ thống dưới các điều kiện tải khác nhau. **Gatling** là công cụ load testing phổ biến cho Java/Scala.

## 2. Metrics quan trọng

### 2.1 Throughput

```
Throughput = Số requests thành công / Thời gian
           = 120,000 requests / 60 seconds
           = 2,000 RPS (requests per second)
```

### 2.2 Latency Percentiles

```
┌─────────────────────────────────────────────────────────────┐
│                    LATENCY DISTRIBUTION                     │
└─────────────────────────────────────────────────────────────┘

    Requests sorted by latency:
    
    ████████████████████████████████████████████████░░ p50 = 15ms
    ████████████████████████████████████████████████████████░░░░ p95 = 45ms
    ████████████████████████████████████████████████████████████░░ p99 = 120ms
    
    Interpretation:
    - 50% of requests complete in ≤ 15ms
    - 95% of requests complete in ≤ 45ms
    - 99% of requests complete in ≤ 120ms
```

### 2.3 Error Rate

```
Error Rate = Failed requests / Total requests × 100%
           = 50 / 120,000 × 100%
           = 0.04%
```

## 3. Gatling Basics

### 3.1 Simulation Structure

```java
public class MySimulation extends Simulation {
    
    // 1. HTTP Protocol
    HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json");
    
    // 2. Scenario (user behavior)
    ScenarioBuilder scenario = scenario("My Scenario")
            .exec(http("Request Name")
                    .post("/api/endpoint")
                    .body(StringBody("{}"))
                    .check(status().is(200)));
    
    // 3. Injection Profile (load pattern)
    {
        setUp(
            scenario.injectOpen(
                constantUsersPerSec(100).during(Duration.ofSeconds(60))
            )
        ).protocols(httpProtocol);
    }
}
```

### 3.2 Injection Profiles

```java
// Constant load
constantUsersPerSec(100).during(Duration.ofSeconds(60))  // 100 RPS for 60s

// Ramp up
rampUsersPerSec(0).to(100).during(Duration.ofSeconds(30))  // 0→100 RPS over 30s

// Stress test
stressPeakUsers(1000).during(Duration.ofSeconds(60))  // Peak at 1000 users

// Spike test
atOnceUsers(500)  // 500 users immediately
```

### 3.3 Checks

```java
.check(status().is(200))                    // Status code
.check(jsonPath("$.decision").exists())     // JSON field exists
.check(responseTimeInMillis().lt(1000))     // Response time < 1s
```

## 4. Benchmark Scenarios

### 4.1 Uniform Load

```java
// 2000 RPS for 60 seconds, random distribution
setUp(
    scenario.injectOpen(
        rampUsersPerSec(1).to(2000).during(Duration.ofSeconds(10)),
        constantUsersPerSec(2000).during(Duration.ofSeconds(60)),
        rampUsersPerSec(2000).to(1).during(Duration.ofSeconds(5))
    )
)
```

### 4.2 Hot Merchant (Skewed Load)

```java
// 70% traffic to one merchant
String merchantId = random.nextDouble() < 0.7 ? "m-hot" : "m-" + random.nextInt(100);
```

### 4.3 Model Slowdown

```java
// Inject latency for 20% of requests
if (random.nextDouble() < 0.2) {
    Thread.sleep(80);  // +80ms latency
}
```

## 5. Analyzing Results

### 5.1 Gatling Report

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         GATLING REPORT                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Request Statistics:                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ Name              │ Count  │ OK     │ KO   │ % OK  │           │   │
│  ├───────────────────┼────────┼────────┼──────┼───────┤           │   │
│  │ POST /authorize   │ 120000 │ 119950 │ 50   │ 99.96 │           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Response Time (ms):                                                    │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ Percentile │ Value │                                           │   │
│  ├────────────┼───────┤                                           │   │
│  │ p50        │ 15    │ ████████████████                          │   │
│  │ p75        │ 25    │ ████████████████████████                  │   │
│  │ p95        │ 45    │ ████████████████████████████████████████  │   │
│  │ p99        │ 120   │ ████████████████████████████████████████████│ │
│  │ max        │ 850   │                                           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Comparison Table

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    VARIANT COMPARISON                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Metric          │ Variant A (Sync) │ Variant B (Async) │ Improvement  │
│  ────────────────┼──────────────────┼───────────────────┼──────────────│
│  Throughput      │ 1,500 RPS        │ 2,200 RPS         │ +47%         │
│  p50 latency     │ 20ms             │ 15ms              │ -25%         │
│  p95 latency     │ 80ms             │ 45ms              │ -44%         │
│  p99 latency     │ 250ms            │ 120ms             │ -52%         │
│  Error rate      │ 0.5%             │ 0.04%             │ -92%         │
│  CPU usage       │ 45%              │ 85%               │ Better util  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## 6. Trong Fraud Detection Gateway

### 6.1 Benchmark Configuration

```java
// UniformSimulation.java
public class UniformSimulation extends Simulation {
    
    HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");
    
    ScenarioBuilder scenario = scenario("Authorize Payment")
            .exec(session -> {
                // Generate unique payment data
                String paymentId = "p-" + counter.incrementAndGet();
                String merchantId = "m-" + (counter.get() % 100);
                return session
                        .set("paymentId", paymentId)
                        .set("merchantId", merchantId);
            })
            .exec(http("POST /payments/authorize")
                    .post("/payments/authorize")
                    .body(StringBody("""
                        {
                            "payment_id": "#{paymentId}",
                            "merchant_id": "#{merchantId}",
                            ...
                        }
                        """))
                    .check(status().is(200))
                    .check(jsonPath("$.decision").exists()));
    
    {
        setUp(
            scenario.injectOpen(
                rampUsersPerSec(1).to(2000).during(Duration.ofSeconds(10)),
                constantUsersPerSec(2000).during(Duration.ofSeconds(60)),
                rampUsersPerSec(2000).to(1).during(Duration.ofSeconds(5))
            )
        ).protocols(httpProtocol)
         .assertions(
             global().responseTime().percentile(95.0).lt(200),
             global().successfulRequests().percent().gt(99.0)
         );
    }
}
```

### 6.2 Running Benchmarks

```bash
# Start infrastructure
make up

# Run application (Variant A or B)
make run

# Run benchmark
make bench:uniform

# Results in: build/reports/gatling/
```

## 7. Best Practices

### 7.1 Warm-up Period

```java
// Always include warm-up before measuring
setUp(
    scenario.injectOpen(
        rampUsersPerSec(1).to(100).during(Duration.ofSeconds(30)),  // Warm-up
        constantUsersPerSec(2000).during(Duration.ofSeconds(60))    // Measure
    )
)
```

### 7.2 Multiple Runs

```bash
# Run 3 times, take median
for i in {1..3}; do
    ./gradlew gatlingRun
    mv build/reports/gatling/uniformsimulation-* results/run-$i/
done
```

### 7.3 Isolate Variables

```
When comparing variants:
1. Same hardware
2. Same JVM settings
3. Same database state
4. Same network conditions
5. Same load pattern
```

## 8. Tham khảo

- [Gatling Documentation](https://gatling.io/docs/gatling/)
- [Gatling Cheat Sheet](https://gatling.io/docs/gatling/reference/current/cheat-sheet/)
- [Performance Testing Best Practices](https://www.brendangregg.com/methodology.html)
