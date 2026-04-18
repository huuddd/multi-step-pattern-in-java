package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Model slowdown simulation: Tests system behavior when model scoring is slow.
 * 
 * This simulates a scenario where the ML model service becomes slow
 * (e.g., due to increased complexity, resource contention, or external dependency).
 * 
 * Expected behavior:
 * - Variant A: Latency increases linearly, throughput drops
 * - Variant B: Thread pool saturates, backpressure kicks in
 * - Variant C: Kafka queue builds up, consumers process at slower rate
 * - Variant D: Per-merchant queues build up independently
 */
public class ModelSlowdownSimulation extends Simulation {

    private static final AtomicLong paymentCounter = new AtomicLong(0);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder authorizeScenario = scenario("Authorize Payment (Model Slowdown)")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                String paymentId = "p-slow-" + id;
                String merchantId = "m-" + (id % 50);
                String idempotencyKey = "idem-" + UUID.randomUUID();
                
                // Vary amount to trigger different model paths
                // Higher amounts may trigger more complex scoring
                long amount = 100000 + (id % 10) * 100000;
                
                return session
                        .set("paymentId", paymentId)
                        .set("merchantId", merchantId)
                        .set("idempotencyKey", idempotencyKey)
                        .set("amount", amount);
            })
            .exec(
                http("POST /payments/authorize")
                    .post("/payments/authorize")
                    .body(StringBody("""
                        {
                            "payment_id": "#{paymentId}",
                            "merchant_id": "#{merchantId}",
                            "amount": #{amount},
                            "currency": "VND",
                            "card_bin": "412345",
                            "ip": "1.2.3.4",
                            "device_id": "d-#{paymentId}",
                            "idempotency_key": "#{idempotencyKey}"
                        }
                        """))
                    .check(status().in(200, 429, 503))
            );

    {
        setUp(
            authorizeScenario.injectOpen(
                // Ramp up to moderate load
                rampUsersPerSec(1).to(500).during(Duration.ofSeconds(10)),
                // Sustain moderate load (model is slow, so lower RPS)
                constantUsersPerSec(500).during(Duration.ofSeconds(60)),
                // Ramp down
                rampUsersPerSec(500).to(1).during(Duration.ofSeconds(5))
            )
        ).protocols(httpProtocol);
    }
}
