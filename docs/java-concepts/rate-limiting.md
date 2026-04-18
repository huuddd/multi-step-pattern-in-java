# Rate Limiting — Lý thuyết chuyên sâu

## Chương 1: Tại sao cần Rate Limiting?

### 1.1 Các vấn đề cần giải quyết

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    RATE LIMITING USE CASES                             │
└─────────────────────────────────────────────────────────────────────────┘

    1. PROTECT RESOURCES
    ┌────────────────────────────────────────────────────────────────────┐
    │  - Database connections có giới hạn                                │
    │  - External APIs có rate limit                                     │
    │  - CPU/Memory có capacity                                          │
    │  - Network bandwidth có giới hạn                                   │
    └────────────────────────────────────────────────────────────────────┘
    
    2. FAIR RESOURCE ALLOCATION
    ┌────────────────────────────────────────────────────────────────────┐
    │  - Merchant A (Basic): 100 RPS                                     │
    │  - Merchant B (Pro): 500 RPS                                       │
    │  - Merchant C (Enterprise): 2000 RPS                               │
    │                                                                    │
    │  Đảm bảo mỗi tenant nhận được đúng quota                          │
    └────────────────────────────────────────────────────────────────────┘
    
    3. PREVENT ABUSE
    ┌────────────────────────────────────────────────────────────────────┐
    │  - DDoS protection                                                 │
    │  - Brute force prevention                                          │
    │  - Scraping prevention                                             │
    │  - Cost control (API calls = money)                                │
    └────────────────────────────────────────────────────────────────────┘
```

### 1.2 Không có Rate Limiting

```
Scenario: Hot merchant gửi 10,000 RPS

    Without Rate Limiting:
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Hot Merchant ────▶ 10,000 RPS ────▶ Database                     │
    │                                           │                        │
    │                                           ▼                        │
    │                                      OVERLOADED!                   │
    │                                           │                        │
    │  Normal Merchants ────▶ 100 RPS ────▶ TIMEOUT                     │
    │                                                                    │
    │  Hậu quả:                                                          │
    │  - Database connection pool exhausted                              │
    │  - All merchants affected                                          │
    │  - System-wide outage                                              │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
    
    With Rate Limiting:
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Hot Merchant ────▶ 10,000 RPS ────▶ Rate Limiter ────▶ 1000 RPS │
    │                                           │                        │
    │                                           ▼                        │
    │                                      9000 RPS REJECTED (429)       │
    │                                                                    │
    │  Normal Merchants ────▶ 100 RPS ────▶ Rate Limiter ────▶ 100 RPS │
    │                                           │                        │
    │                                           ▼                        │
    │                                      ALL ACCEPTED ✓                │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

---

## Chương 2: Rate Limiting Algorithms

### 2.1 Token Bucket

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       TOKEN BUCKET ALGORITHM                            │
└─────────────────────────────────────────────────────────────────────────┘

    Bucket capacity: 10 tokens
    Refill rate: 5 tokens/second
    
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  Initial: [●●●●●●●●●●] (10 tokens)                             │
    │                                                                 │
    │  t=0.0s: Request 1 → Take 1 → [●●●●●●●●●○] (9 tokens) ✓        │
    │  t=0.1s: Request 2 → Take 1 → [●●●●●●●●○○] (8 tokens) ✓        │
    │  t=0.2s: Request 3 → Take 1 → [●●●●●●●○○○] (7 tokens) ✓        │
    │  ...                                                            │
    │  t=0.9s: Request 10 → Take 1 → [○○○○○○○○○○] (0 tokens) ✓       │
    │  t=1.0s: Request 11 → No token → REJECT (429)                   │
    │                                                                 │
    │  t=1.0s: Refill 5 tokens → [●●●●●○○○○○] (5 tokens)             │
    │  t=1.0s: Request 12 → Take 1 → [●●●●○○○○○○] (4 tokens) ✓       │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘

    Đặc điểm:
    - Cho phép burst (tối đa = bucket capacity)
    - Smooth out traffic over time
    - Memory efficient (chỉ cần lưu: tokens, last_refill_time)
```

**Implementation:**

```java
public class TokenBucket {
    private final double capacity;
    private final double refillRate;  // tokens per second
    private double tokens;
    private long lastRefillTime;
    
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
    
    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTime) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsed * refillRate);
        lastRefillTime = now;
    }
}
```

### 2.2 Leaky Bucket

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       LEAKY BUCKET ALGORITHM                            │
└─────────────────────────────────────────────────────────────────────────┘

    Bucket capacity: 10 requests
    Leak rate: 5 requests/second (constant output)
    
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │        ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼  (10 requests arrive)              │
    │       ┌───────────────────┐                                     │
    │       │ ● ● ● ● ● ● ● ● ● │  Bucket (queue)                    │
    │       │ ● ○ ○ ○ ○ ○ ○ ○ ○ │                                     │
    │       └─────────┬─────────┘                                     │
    │                 │                                               │
    │                 ▼ (leak at constant rate: 5/s)                  │
    │            ┌─────────┐                                          │
    │            │ Process │                                          │
    │            └─────────┘                                          │
    │                                                                 │
    │  Nếu bucket đầy → REJECT request mới                           │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘

    Đặc điểm:
    - Output rate constant (không có burst)
    - Smooth traffic
    - Requests được queue (có latency)
```

### 2.3 Fixed Window Counter

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    FIXED WINDOW COUNTER                                 │
└─────────────────────────────────────────────────────────────────────────┘

    Limit: 100 requests per minute
    
    Window 1 (00:00 - 00:59)    Window 2 (01:00 - 01:59)
    ┌───────────────────────┐   ┌───────────────────────┐
    │ Count: 0 → 50 → 100   │   │ Count: 0 → 50 → 100   │
    │                       │   │                       │
    │ t=00:30: 50 requests  │   │ t=01:30: 50 requests  │
    │ t=00:59: 50 requests  │   │ t=01:01: 50 requests  │
    │ Total: 100 ✓          │   │ Total: 100 ✓          │
    └───────────────────────┘   └───────────────────────┘
    
    VẤN ĐỀ: Boundary burst
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  t=00:59: 100 requests (end of window 1)                       │
    │  t=01:00: 100 requests (start of window 2)                     │
    │                                                                 │
    │  → 200 requests trong 2 giây! (gấp đôi limit)                  │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

### 2.4 Sliding Window Log

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    SLIDING WINDOW LOG                                   │
└─────────────────────────────────────────────────────────────────────────┘

    Limit: 100 requests per minute
    
    Lưu timestamp của mỗi request:
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  Log: [00:30:15, 00:30:16, 00:30:17, ..., 01:29:59]            │
    │                                                                 │
    │  Khi request mới đến (t=01:30:00):                             │
    │  1. Xóa entries cũ hơn 1 phút (< 00:30:00)                     │
    │  2. Đếm entries còn lại                                        │
    │  3. Nếu count < 100 → ACCEPT, thêm timestamp                   │
    │  4. Nếu count >= 100 → REJECT                                  │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘

    Đặc điểm:
    - Chính xác nhất
    - Memory intensive (lưu tất cả timestamps)
    - Không có boundary burst problem
```

### 2.5 Sliding Window Counter (Hybrid)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    SLIDING WINDOW COUNTER                               │
└─────────────────────────────────────────────────────────────────────────┘

    Limit: 100 requests per minute
    
    Kết hợp Fixed Window + weighted average:
    
    Current time: 01:15 (15 seconds into window 2)
    
    Window 1 (00:00-00:59): 80 requests
    Window 2 (01:00-01:59): 30 requests (so far)
    
    Weighted count = (Window1 * overlap%) + Window2
                   = (80 * 75%) + 30
                   = 60 + 30
                   = 90 requests
    
    90 < 100 → ACCEPT
    
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  Window 1          Window 2                                     │
    │  ┌─────────────┐   ┌─────────────┐                             │
    │  │    80 req   │   │   30 req    │                             │
    │  └─────────────┘   └─────────────┘                             │
    │        ◀──75%──▶   ◀──25%──▶                                   │
    │        └────────────────────┘                                   │
    │              Sliding window                                     │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘

    Đặc điểm:
    - Memory efficient (chỉ 2 counters)
    - Approximate nhưng đủ tốt
    - Không có boundary burst problem
```

### 2.6 So sánh Algorithms

| Algorithm | Memory | Accuracy | Burst | Use Case |
|-----------|--------|----------|-------|----------|
| Token Bucket | O(1) | High | Allows | API rate limiting |
| Leaky Bucket | O(n) | High | No | Traffic shaping |
| Fixed Window | O(1) | Low | Boundary | Simple cases |
| Sliding Log | O(n) | Highest | No | Audit required |
| Sliding Counter | O(1) | High | Minimal | General purpose |

---

## Chương 3: Guava RateLimiter

### 3.1 Cơ bản

```java
// Create rate limiter: 5 permits per second
RateLimiter limiter = RateLimiter.create(5.0);

// Blocking acquire
limiter.acquire();  // Blocks until permit available

// Non-blocking try
if (limiter.tryAcquire()) {
    // Got permit
} else {
    // Rate limited
}

// Try with timeout
if (limiter.tryAcquire(1, TimeUnit.SECONDS)) {
    // Got permit within 1 second
}
```

### 3.2 Warm-up Period

```java
// Warm-up: gradually increase rate over 10 seconds
RateLimiter limiter = RateLimiter.create(5.0, 10, TimeUnit.SECONDS);

// t=0s: ~0.5 permits/s
// t=5s: ~2.5 permits/s
// t=10s: 5.0 permits/s (full rate)
```

### 3.3 Per-Tenant Rate Limiting

```java
@Component
public class PerTenantRateLimiter {
    
    private final LoadingCache<String, RateLimiter> limiters = 
        CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public RateLimiter load(String tenantId) {
                    double rate = getTenantRate(tenantId);
                    return RateLimiter.create(rate);
                }
            });
    
    public boolean tryAcquire(String tenantId) {
        return limiters.getUnchecked(tenantId).tryAcquire();
    }
    
    private double getTenantRate(String tenantId) {
        // Lookup from config/database
        return switch (getTenantTier(tenantId)) {
            case BASIC -> 100.0;
            case PRO -> 500.0;
            case ENTERPRISE -> 2000.0;
        };
    }
}
```

---

## Chương 4: Distributed Rate Limiting

### 4.1 Vấn đề với Multiple Instances

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DISTRIBUTED RATE LIMITING                           │
└─────────────────────────────────────────────────────────────────────────┘

    Single Instance (OK):
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Client ────▶ Instance 1 ────▶ RateLimiter (100 RPS)              │
    │                                                                    │
    │  100 RPS limit enforced correctly ✓                               │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
    
    Multiple Instances (Problem):
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Client ────▶ LB ────▶ Instance 1 ────▶ RateLimiter (100 RPS)     │
    │                  │                                                 │
    │                  └──▶ Instance 2 ────▶ RateLimiter (100 RPS)      │
    │                  │                                                 │
    │                  └──▶ Instance 3 ────▶ RateLimiter (100 RPS)      │
    │                                                                    │
    │  Actual limit: 300 RPS! (3 × 100)                                 │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

### 4.2 Giải pháp: Redis-based Rate Limiting

```java
@Component
public class RedisRateLimiter {
    
    private final StringRedisTemplate redis;
    
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = "rate:" + key;
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        
        // Lua script for atomic operation
        String script = """
            -- Remove old entries
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
            -- Count current entries
            local count = redis.call('ZCARD', KEYS[1])
            if count < tonumber(ARGV[2]) then
                -- Add new entry
                redis.call('ZADD', KEYS[1], ARGV[3], ARGV[3])
                redis.call('EXPIRE', KEYS[1], ARGV[4])
                return 1
            end
            return 0
            """;
        
        Long result = redis.execute(
            RedisScript.of(script, Long.class),
            List.of(redisKey),
            String.valueOf(windowStart),
            String.valueOf(limit),
            String.valueOf(now),
            String.valueOf(window.toSeconds())
        );
        
        return result == 1;
    }
}
```

---

## Chương 5: Trong Fraud Detection Gateway

### 5.1 Per-Merchant Rate Limiting

```java
@Component
public class PerMerchantRateLimiter {
    
    private final LoadingCache<String, RateLimiter> rateLimiters;
    
    public boolean tryAcquire(String merchantId) {
        RateLimiter limiter = rateLimiters.getUnchecked(merchantId);
        return limiter.tryAcquire();
    }
}
```

### 5.2 Integration với Consumer

```java
@RabbitListener(queues = "merchant.${merchantId}")
public void consume(PaymentEvent event, Channel channel, long deliveryTag) {
    String merchantId = event.getMerchantId();
    
    // Rate limiting check
    if (!rateLimiter.tryAcquire(merchantId)) {
        log.warn("Rate limit exceeded for merchant: {}", merchantId);
        // Requeue for later
        channel.basicNack(deliveryTag, false, true);
        return;
    }
    
    // Process normally
    process(event);
    channel.basicAck(deliveryTag, false);
}
```

---

## Tham khảo

- [Guava RateLimiter](https://github.com/google/guava/wiki/CachesExplained)
- [Rate Limiting Strategies](https://cloud.google.com/architecture/rate-limiting-strategies-techniques)
- [Stripe Rate Limiting](https://stripe.com/blog/rate-limiters)
- [Redis Rate Limiting](https://redis.io/commands/incr#pattern-rate-limiter)
