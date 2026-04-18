package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Uniform load simulation: 2000 RPS for 60 seconds.
 */
public class UniformSimulation extends Simulation {

    private static final AtomicLong paymentCounter = new AtomicLong(0);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder authorizeScenario = scenario("Authorize Payment")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                String paymentId = "p-" + id;
                String merchantId = "m-" + (id % 100); // 100 merchants
                String idempotencyKey = "idem-" + UUID.randomUUID();
                
                return session
                        .set("paymentId", paymentId)
                        .set("merchantId", merchantId)
                        .set("idempotencyKey", idempotencyKey)
                        .set("amount", 100000 + (id % 900000)); // 100k - 1M VND
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
                    .check(status().is(200))
                    .check(jsonPath("$.decision").exists())
            );

    {
        setUp(
            authorizeScenario.injectOpen(
                // Ramp up to 2000 RPS over 10 seconds
                rampUsersPerSec(1).to(2000).during(Duration.ofSeconds(10)),
                // Hold at 2000 RPS for 60 seconds
                constantUsersPerSec(2000).during(Duration.ofSeconds(60)),
                // Ramp down over 5 seconds
                rampUsersPerSec(2000).to(1).during(Duration.ofSeconds(5))
            )
        ).protocols(httpProtocol)
         .assertions(
             global().responseTime().percentile(95.0).lt(200),
             global().successfulRequests().percent().gt(99.0)
         );
    }
}
