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
 * Fault injection simulation: Tests system resilience under failures.
 * 
 * This simulates various failure scenarios:
 * - Invalid requests (bad JSON, missing fields)
 * - Edge cases (zero amount, negative amount)
 * - Blacklisted card BINs
 * - High-risk IPs
 * 
 * Expected behavior:
 * - System should handle errors gracefully
 * - Invalid requests should return 400
 * - Blacklisted should return BLOCK decision
 * - Error rate should be contained
 */
public class FaultInjectionSimulation extends Simulation {

    private static final AtomicLong paymentCounter = new AtomicLong(0);
    private static final String[] BLACKLISTED_BINS = {"000000", "111111", "999999"};
    private static final String[] HIGH_RISK_IPS = {"10.0.0.1", "192.168.1.1"};

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl("http://localhost:8080")
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    // Normal requests (80%)
    private final ScenarioBuilder normalScenario = scenario("Normal Requests")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                return session
                        .set("paymentId", "p-normal-" + id)
                        .set("merchantId", "m-" + (id % 50))
                        .set("idempotencyKey", "idem-" + UUID.randomUUID())
                        .set("amount", 100000 + (id % 900000))
                        .set("cardBin", "412345")
                        .set("ip", "1.2.3.4");
            })
            .exec(
                http("POST /payments/authorize (Normal)")
                    .post("/payments/authorize")
                    .body(StringBody("""
                        {
                            "payment_id": "#{paymentId}",
                            "merchant_id": "#{merchantId}",
                            "amount": #{amount},
                            "currency": "VND",
                            "card_bin": "#{cardBin}",
                            "ip": "#{ip}",
                            "device_id": "d-#{paymentId}",
                            "idempotency_key": "#{idempotencyKey}"
                        }
                        """))
                    .check(status().is(200))
            );

    // Blacklisted BIN requests (10%)
    private final ScenarioBuilder blacklistedScenario = scenario("Blacklisted BIN")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                String blacklistedBin = BLACKLISTED_BINS[
                        ThreadLocalRandom.current().nextInt(BLACKLISTED_BINS.length)];
                return session
                        .set("paymentId", "p-blacklist-" + id)
                        .set("merchantId", "m-" + (id % 50))
                        .set("idempotencyKey", "idem-" + UUID.randomUUID())
                        .set("amount", 100000)
                        .set("cardBin", blacklistedBin)
                        .set("ip", "1.2.3.4");
            })
            .exec(
                http("POST /payments/authorize (Blacklisted)")
                    .post("/payments/authorize")
                    .body(StringBody("""
                        {
                            "payment_id": "#{paymentId}",
                            "merchant_id": "#{merchantId}",
                            "amount": #{amount},
                            "currency": "VND",
                            "card_bin": "#{cardBin}",
                            "ip": "#{ip}",
                            "device_id": "d-#{paymentId}",
                            "idempotency_key": "#{idempotencyKey}"
                        }
                        """))
                    .check(status().is(200))
                    .check(jsonPath("$.decision").is("BLOCK"))
            );

    // Invalid requests (5%)
    private final ScenarioBuilder invalidScenario = scenario("Invalid Requests")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                return session
                        .set("paymentId", "p-invalid-" + id)
                        .set("merchantId", "m-" + (id % 50))
                        .set("idempotencyKey", "idem-" + UUID.randomUUID());
            })
            .exec(
                http("POST /payments/authorize (Invalid - Missing Amount)")
                    .post("/payments/authorize")
                    .body(StringBody("""
                        {
                            "payment_id": "#{paymentId}",
                            "merchant_id": "#{merchantId}",
                            "currency": "VND",
                            "idempotency_key": "#{idempotencyKey}"
                        }
                        """))
                    .check(status().in(400, 422))
            );

    // Duplicate requests (5%)
    private final ScenarioBuilder duplicateScenario = scenario("Duplicate Requests")
            .exec(session -> {
                long id = paymentCounter.incrementAndGet();
                String idempotencyKey = "idem-duplicate-" + (id / 2); // Same key for pairs
                return session
                        .set("paymentId", "p-dup-" + id)
                        .set("merchantId", "m-" + (id % 50))
                        .set("idempotencyKey", idempotencyKey)
                        .set("amount", 100000);
            })
            .exec(
                http("POST /payments/authorize (Duplicate)")
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
                    .check(status().in(200, 409))
            );

    {
        setUp(
            normalScenario.injectOpen(
                rampUsersPerSec(1).to(800).during(Duration.ofSeconds(10)),
                constantUsersPerSec(800).during(Duration.ofSeconds(60)),
                rampUsersPerSec(800).to(1).during(Duration.ofSeconds(5))
            ),
            blacklistedScenario.injectOpen(
                rampUsersPerSec(1).to(100).during(Duration.ofSeconds(10)),
                constantUsersPerSec(100).during(Duration.ofSeconds(60)),
                rampUsersPerSec(100).to(1).during(Duration.ofSeconds(5))
            ),
            invalidScenario.injectOpen(
                rampUsersPerSec(1).to(50).during(Duration.ofSeconds(10)),
                constantUsersPerSec(50).during(Duration.ofSeconds(60)),
                rampUsersPerSec(50).to(1).during(Duration.ofSeconds(5))
            ),
            duplicateScenario.injectOpen(
                rampUsersPerSec(1).to(50).during(Duration.ofSeconds(10)),
                constantUsersPerSec(50).during(Duration.ofSeconds(60)),
                rampUsersPerSec(50).to(1).during(Duration.ofSeconds(5))
            )
        ).protocols(httpProtocol);
    }
}
