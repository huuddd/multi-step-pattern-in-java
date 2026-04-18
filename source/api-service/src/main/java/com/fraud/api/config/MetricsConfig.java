package com.fraud.api.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration for custom metrics.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public InFlightRequestsTracker inFlightRequestsTracker() {
        return new InFlightRequestsTracker();
    }

    @Bean
    public MeterBinder customMetrics(InFlightRequestsTracker tracker) {
        return registry -> {
            // In-flight requests gauge
            Gauge.builder("authorize_requests_inflight", tracker, InFlightRequestsTracker::get)
                    .description("Number of authorize requests currently being processed")
                    .register(registry);

            // Decision counters will be created dynamically
            // Step latency timers will be created dynamically in FraudDetectionPipeline
        };
    }

    /**
     * Tracks number of in-flight requests.
     */
    public static class InFlightRequestsTracker {
        private final AtomicLong count = new AtomicLong(0);

        public void increment() {
            count.incrementAndGet();
        }

        public void decrement() {
            count.decrementAndGet();
        }

        public long get() {
            return count.get();
        }
    }
}
