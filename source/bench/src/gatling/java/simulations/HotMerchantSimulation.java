package simulations;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Hot merchant simulation: 70% traffic goes to m-hot merchant.
 */
public class HotMerchantSimulation extends Simulation {

    private static final AtomicLong paymentCounter = new AtomicLong(0);
    private static final String HOT_MERCHANT = "m-hot";

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder authorizeScenario = scenario("Authorize Payment (Hot Merchant)")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                String paymentId = "p-" + id;
                
                // 70% traffic to hot merchant
                String merchantId = ThreadLocalRandom.current().nextDouble() < 0.7 
                        ? HOT_MERCHANT 
                        : "m-" + (id % 100);
                
                String idempotencyKey = "idem-" + UUID.randomUUID();
                
                return session
                        .set("paymentId", paymentId)
                        .set("merchantId", merchantId)
                        .set("idempotencyKey", idempotencyKey)
                        .set("amount", 100000 + (id % 900000));
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
                rampUsersPerSec(1).to(2000).during(Duration.ofSeconds(10)),
                constantUsersPerSec(2000).during(Duration.ofSeconds(60)),
                rampUsersPerSec(2000).to(1).during(Duration.ofSeconds(5))
            )
        ).protocols(httpProtocol);
    }
}
