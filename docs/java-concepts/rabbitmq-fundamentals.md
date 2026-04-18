# RabbitMQ — Nền tảng lý thuyết

## Chương 1: Giới thiệu

### 1.1 RabbitMQ là gì?

**RabbitMQ** là một **message broker** implement AMQP (Advanced Message Queuing Protocol). Khác với Kafka (log-based), RabbitMQ là **queue-based** — messages được consume và xóa khỏi queue.

### 1.2 So sánh Kafka vs RabbitMQ

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    KAFKA vs RABBITMQ                                    │
└─────────────────────────────────────────────────────────────────────────┘

    KAFKA (Log-based)                    RABBITMQ (Queue-based)
    ┌─────────────────────┐              ┌─────────────────────┐
    │                     │              │                     │
    │  ┌───┬───┬───┬───┐  │              │  ┌───┬───┬───┬───┐  │
    │  │ 0 │ 1 │ 2 │ 3 │  │              │  │ A │ B │ C │ D │  │
    │  └───┴───┴───┴───┘  │              │  └───┴───┴───┴───┘  │
    │        ▲             │              │    │               │
    │        │             │              │    ▼               │
    │     Consumer         │              │  Consumer          │
    │     (offset 2)       │              │  (dequeue A)       │
    │                     │              │                     │
    │  Messages RETAINED   │              │  Messages DELETED  │
    │  after consume       │              │  after consume     │
    │                     │              │                     │
    └─────────────────────┘              └─────────────────────┘

    | Aspect        | Kafka              | RabbitMQ           |
    |---------------|--------------------|--------------------|
    | Model         | Log (append-only)  | Queue (FIFO)       |
    | Retention     | Time/size based    | Until consumed     |
    | Replay        | Yes (offset reset) | No                 |
    | Routing       | Partition key      | Exchange + routing |
    | Ordering      | Per partition      | Per queue          |
    | Use case      | Event streaming    | Task queue         |
```

### 1.3 Khi nào dùng RabbitMQ?

- **Task distribution** — distribute work to workers
- **Complex routing** — route based on multiple criteria
- **Request-reply** — RPC pattern
- **Per-tenant isolation** — separate queues per tenant
- **Priority queues** — process high-priority first
- **Message TTL** — expire messages after timeout

---

## Chương 2: Core Concepts

### 2.1 AMQP Model

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         AMQP MODEL                                      │
└─────────────────────────────────────────────────────────────────────────┘

    Producer                                              Consumer
       │                                                      ▲
       │ publish(exchange, routing_key, message)              │
       ▼                                                      │
    ┌─────────────────────────────────────────────────────────┴───────┐
    │                         BROKER                                  │
    │                                                                 │
    │  ┌──────────────┐     Binding      ┌──────────────┐            │
    │  │   Exchange   │ ═══════════════▶ │    Queue     │            │
    │  │              │  (routing_key)   │              │            │
    │  └──────────────┘                  └──────────────┘            │
    │                                                                 │
    │  Exchange types:                   Queue properties:           │
    │  - direct                          - durable                   │
    │  - fanout                          - exclusive                 │
    │  - topic                           - auto-delete               │
    │  - headers                         - arguments (TTL, DLQ)      │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

### 2.2 Exchange Types

#### Direct Exchange

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       DIRECT EXCHANGE                                   │
└─────────────────────────────────────────────────────────────────────────┘

    Producer                                              Consumers
       │                                                      
       │ routing_key = "merchant-123"                         
       ▼                                                      
    ┌──────────────┐                                          
    │   Exchange   │                                          
    │   (direct)   │                                          
    └──────┬───────┘                                          
           │                                                  
           │ Match routing_key exactly                        
           │                                                  
    ┌──────┼──────────────────────────────────────────┐       
    │      │                                          │       
    │      ▼                                          ▼       
    │ ┌──────────────┐                         ┌──────────────┐
    │ │Queue: m-123  │ binding: "merchant-123" │Queue: m-456  │
    │ │              │                         │              │
    │ └──────────────┘                         └──────────────┘
    │      │                                          │       
    │      ▼                                          ▼       
    │ ┌──────────┐                              ┌──────────┐  
    │ │Consumer 1│                              │Consumer 2│  
    │ └──────────┘                              └──────────┘  
    │                                                         
    └─────────────────────────────────────────────────────────┘

    Message với routing_key="merchant-123" → Queue m-123
    Message với routing_key="merchant-456" → Queue m-456
```

#### Fanout Exchange

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       FANOUT EXCHANGE                                   │
└─────────────────────────────────────────────────────────────────────────┘

    Producer                                              
       │                                                  
       │ (routing_key ignored)                            
       ▼                                                  
    ┌──────────────┐                                      
    │   Exchange   │                                      
    │   (fanout)   │                                      
    └──────┬───────┘                                      
           │                                              
           │ Broadcast to ALL bound queues                
           │                                              
    ┌──────┼──────────────────────────────────────────┐   
    │      │              │              │            │   
    │      ▼              ▼              ▼            │   
    │ ┌─────────┐   ┌─────────┐   ┌─────────┐        │   
    │ │ Queue A │   │ Queue B │   │ Queue C │        │   
    │ └─────────┘   └─────────┘   └─────────┘        │   
    │                                                 │   
    └─────────────────────────────────────────────────┘   

    Mọi message đều được copy đến TẤT CẢ queues
    Use case: Broadcast notifications, logging
```

#### Topic Exchange

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       TOPIC EXCHANGE                                    │
└─────────────────────────────────────────────────────────────────────────┘

    Producer                                              
       │                                                  
       │ routing_key = "payment.vn.high"                  
       ▼                                                  
    ┌──────────────┐                                      
    │   Exchange   │                                      
    │   (topic)    │                                      
    └──────┬───────┘                                      
           │                                              
           │ Pattern matching với wildcards               
           │ * = exactly one word                         
           │ # = zero or more words                       
           │                                              
    ┌──────┼──────────────────────────────────────────┐   
    │      │              │              │            │   
    │      ▼              ▼              ▼            │   
    │ ┌─────────┐   ┌─────────┐   ┌─────────┐        │   
    │ │payment.*│   │*.vn.*   │   │#.high   │        │   
    │ │.high    │   │         │   │         │        │   
    │ └─────────┘   └─────────┘   └─────────┘        │   
    │    ✓              ✓              ✓              │   
    │                                                 │   
    └─────────────────────────────────────────────────┘   

    "payment.vn.high" matches:
    - "payment.*.high" ✓
    - "*.vn.*" ✓
    - "#.high" ✓
```

### 2.3 Queue Properties

```java
// Durable: survive broker restart
// Exclusive: only one connection can use
// Auto-delete: delete when last consumer disconnects

@Bean
public Queue merchantQueue() {
    return QueueBuilder.durable("merchant-123")
            .withArgument("x-message-ttl", 60000)      // TTL 60s
            .withArgument("x-dead-letter-exchange", "dlx")
            .withArgument("x-max-length", 10000)       // Max 10k messages
            .build();
}
```

---

## Chương 3: Message Acknowledgment

### 3.1 Ack Modes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    ACKNOWLEDGMENT MODES                                 │
└─────────────────────────────────────────────────────────────────────────┘

    1. AUTO ACK (Risky)
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Queue ──▶ Deliver ──▶ ACK (immediate) ──▶ Process                │
    │                              │                    │                │
    │                              │                    ▼                │
    │                         Message removed      CRASH!                │
    │                         from queue           Message LOST          │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
    
    2. MANUAL ACK (Recommended)
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Queue ──▶ Deliver ──▶ Process ──▶ ACK (manual)                   │
    │                           │              │                         │
    │                           │              ▼                         │
    │                       Success?      Message removed                │
    │                           │                                        │
    │                       ┌───┴───┐                                    │
    │                       │       │                                    │
    │                      YES     NO                                    │
    │                       │       │                                    │
    │                       ▼       ▼                                    │
    │                     ACK     NACK/REJECT                            │
    │                              │                                     │
    │                              ▼                                     │
    │                         Requeue or DLQ                             │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

### 3.2 Prefetch Count

**Prefetch** = số messages consumer nhận trước khi phải ack.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       PREFETCH COUNT                                    │
└─────────────────────────────────────────────────────────────────────────┘

    prefetchCount = 1 (Fair dispatch)
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Queue: [M1, M2, M3, M4, M5, M6]                                  │
    │                                                                    │
    │  Consumer A (fast):  M1 ──▶ ACK ──▶ M3 ──▶ ACK ──▶ M5 ──▶ ACK    │
    │  Consumer B (slow):  M2 ──────────────▶ ACK ──▶ M4 ──────────▶    │
    │                                                                    │
    │  Mỗi consumer chỉ nhận 1 message tại một thời điểm                │
    │  → Fair distribution, slow consumer không bị overwhelm            │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
    
    prefetchCount = 10 (Batch processing)
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Queue: [M1...M20]                                                │
    │                                                                    │
    │  Consumer A: [M1-M10] ──▶ Process batch ──▶ ACK all               │
    │  Consumer B: [M11-M20] ──▶ Process batch ──▶ ACK all              │
    │                                                                    │
    │  Higher throughput, but unfair if processing time varies          │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

---

## Chương 4: Per-Tenant Routing Pattern

### 4.1 Vấn đề: Noisy Neighbor

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    NOISY NEIGHBOR PROBLEM                               │
└─────────────────────────────────────────────────────────────────────────┘

    Single Queue (shared):
    ┌────────────────────────────────────────────────────────────────────┐
    │                                                                    │
    │  Queue: [M1-hot, M2-hot, M3-hot, M4-hot, M5-normal, M6-normal]    │
    │                                                                    │
    │  "Hot" merchant gửi 1000 RPS                                      │
    │  "Normal" merchants gửi 10 RPS mỗi                                │
    │                                                                    │
    │  Consumer processes: hot, hot, hot, hot, hot, ...                 │
    │                                                                    │
    │  → Normal merchants bị starve!                                    │
    │  → Latency của normal merchants tăng vọt                          │
    │                                                                    │
    └────────────────────────────────────────────────────────────────────┘
```

### 4.2 Giải pháp: Per-Tenant Queues

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PER-TENANT QUEUE PATTERN                            │
└─────────────────────────────────────────────────────────────────────────┘

    Direct Exchange
         │
         │ routing_key = merchant_id
         │
    ┌────┴────────────────────────────────────────────────────────────┐
    │                                                                 │
    │    ▼              ▼              ▼              ▼              │
    │ ┌──────┐      ┌──────┐      ┌──────┐      ┌──────┐            │
    │ │m-hot │      │m-001 │      │m-002 │      │m-003 │            │
    │ │1000  │      │ 10   │      │ 10   │      │ 10   │            │
    │ │msg/s │      │msg/s │      │msg/s │      │msg/s │            │
    │ └──┬───┘      └──┬───┘      └──┬───┘      └──┬───┘            │
    │    │             │             │             │                 │
    │    ▼             ▼             ▼             ▼                 │
    │ ┌──────┐      ┌──────┐      ┌──────┐      ┌──────┐            │
    │ │ C1   │      │ C2   │      │ C3   │      │ C4   │            │
    │ │ C2   │      │      │      │      │      │      │            │
    │ │ C3   │      │      │      │      │      │      │            │
    │ └──────┘      └──────┘      └──────┘      └──────┘            │
    │                                                                 │
    │  Hot merchant có 3 consumers                                   │
    │  Normal merchants có 1 consumer mỗi                            │
    │  → Isolation! Normal merchants không bị ảnh hưởng              │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

### 4.3 Dynamic Queue Creation

```java
// Tạo queue động khi merchant mới xuất hiện
public void ensureQueueExists(String merchantId) {
    String queueName = "merchant." + merchantId;
    
    // Declare queue (idempotent)
    rabbitAdmin.declareQueue(
        QueueBuilder.durable(queueName)
            .withArgument("x-message-ttl", 300000)  // 5 min TTL
            .withArgument("x-dead-letter-exchange", "dlx")
            .build()
    );
    
    // Bind to exchange
    rabbitAdmin.declareBinding(
        BindingBuilder.bind(new Queue(queueName))
            .to(new DirectExchange("risk.direct"))
            .with(merchantId)  // routing key = merchantId
    );
}
```

---

## Chương 5: Rate Limiting Per Tenant

### 5.1 Tại sao cần Rate Limiting?

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    RATE LIMITING SCENARIOS                             │
└─────────────────────────────────────────────────────────────────────────┘

    1. PROTECT DOWNSTREAM SERVICES
       - Database có giới hạn connections
       - External API có rate limit
       - Model service có capacity limit
    
    2. FAIR RESOURCE ALLOCATION
       - Merchant A trả $100/month → 100 RPS
       - Merchant B trả $1000/month → 1000 RPS
    
    3. PREVENT ABUSE
       - Detect và throttle suspicious traffic
       - DDoS protection
```

### 5.2 Token Bucket Algorithm

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    TOKEN BUCKET ALGORITHM                               │
└─────────────────────────────────────────────────────────────────────────┘

    Bucket capacity: 10 tokens
    Refill rate: 5 tokens/second
    
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                 │
    │  Bucket: [●●●●●●●●●●]  (10 tokens)                             │
    │                                                                 │
    │  Request 1: Take 1 token → [●●●●●●●●●○]  (9 tokens) ✓          │
    │  Request 2: Take 1 token → [●●●●●●●●○○]  (8 tokens) ✓          │
    │  ...                                                            │
    │  Request 10: Take 1 token → [○○○○○○○○○○] (0 tokens) ✓          │
    │  Request 11: No token → REJECT (429 Too Many Requests)          │
    │                                                                 │
    │  After 1 second: Refill 5 tokens → [●●●●●○○○○○] (5 tokens)     │
    │                                                                 │
    └─────────────────────────────────────────────────────────────────┘
```

### 5.3 Implementation với Guava RateLimiter

```java
@Component
public class PerMerchantRateLimiter {
    
    // Cache of rate limiters per merchant
    private final LoadingCache<String, RateLimiter> rateLimiters = 
        CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(new CacheLoader<>() {
                @Override
                public RateLimiter load(String merchantId) {
                    // Default: 100 RPS per merchant
                    double permitsPerSecond = getMerchantRateLimit(merchantId);
                    return RateLimiter.create(permitsPerSecond);
                }
            });
    
    public boolean tryAcquire(String merchantId) {
        RateLimiter limiter = rateLimiters.getUnchecked(merchantId);
        return limiter.tryAcquire();  // Non-blocking
    }
    
    public void acquire(String merchantId) {
        RateLimiter limiter = rateLimiters.getUnchecked(merchantId);
        limiter.acquire();  // Blocking until permit available
    }
}
```

---

## Chương 6: Trong Fraud Detection Gateway

### 6.1 Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PER-MERCHANT RABBITMQ ARCHITECTURE                  │
└─────────────────────────────────────────────────────────────────────────┘

    POST /authorize
         │
         ▼
    ┌─────────────────┐
    │   API Service   │
    │   (Publisher)   │
    └────────┬────────┘
             │
             │ publish(exchange="risk.direct", 
             │         routing_key=merchant_id)
             ▼
    ╔═════════════════════════════════════════════════════════════════════╗
    ║                       RABBITMQ BROKER                               ║
    ╠═════════════════════════════════════════════════════════════════════╣
    ║                                                                     ║
    ║  ┌─────────────────────────────────────────────────────────────┐   ║
    ║  │                    risk.direct (Exchange)                    │   ║
    ║  └──────────────────────────┬──────────────────────────────────┘   ║
    ║                             │                                       ║
    ║         ┌───────────────────┼───────────────────┐                  ║
    ║         │                   │                   │                  ║
    ║         ▼                   ▼                   ▼                  ║
    ║  ┌────────────┐      ┌────────────┐      ┌────────────┐           ║
    ║  │merchant.001│      │merchant.002│      │merchant.003│           ║
    ║  │  (Queue)   │      │  (Queue)   │      │  (Queue)   │           ║
    ║  └─────┬──────┘      └─────┬──────┘      └─────┬──────┘           ║
    ║        │                   │                   │                   ║
    ╚════════╪═══════════════════╪═══════════════════╪═══════════════════╝
             │                   │                   │
             ▼                   ▼                   ▼
    ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
    │   Consumer 1    │ │   Consumer 2    │ │   Consumer 3    │
    │ (prefetch=1)    │ │ (prefetch=1)    │ │ (prefetch=1)    │
    │ + RateLimiter   │ │ + RateLimiter   │ │ + RateLimiter   │
    └────────┬────────┘ └────────┬────────┘ └────────┬────────┘
             │                   │                   │
             └───────────────────┴───────────────────┘
                                 │
                                 ▼
                          ┌─────────────┐
                          │  PostgreSQL │
                          │  + Webhook  │
                          └─────────────┘
```

---

## Tham khảo

- [RabbitMQ Tutorials](https://www.rabbitmq.com/getstarted.html)
- [AMQP 0-9-1 Model](https://www.rabbitmq.com/tutorials/amqp-concepts.html)
- [Spring AMQP Documentation](https://docs.spring.io/spring-amqp/reference/)
- [Rate Limiting Patterns](https://stripe.com/blog/rate-limiters)
