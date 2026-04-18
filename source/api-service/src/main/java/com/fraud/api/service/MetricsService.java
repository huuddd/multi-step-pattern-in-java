package com.fraud.api.service;

import com.fraud.api.repository.PaymentRepository;
import com.fraud.common.domain.Decision;
import com.fraud.common.dto.MetricsStatsResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final PaymentRepository paymentRepository;

    public MetricsStatsResponse getStats() {
        return MetricsStatsResponse.builder()
                .throughput(getThroughputStats())
                .latency(getLatencyStats())
                .queueDepth(getQueueDepth())
                .decisions(getDecisionCounts())
                .build();
    }

    private MetricsStatsResponse.ThroughputStats getThroughputStats() {
        // Get rate from counter
        double rps1m = getRate("authorize_requests_total", 60);
        double rps5m = getRate("authorize_requests_total", 300);
        
        return MetricsStatsResponse.ThroughputStats.builder()
                .rps1m(rps1m)
                .rps5m(rps5m)
                .build();
    }

    private MetricsStatsResponse.LatencyStats getLatencyStats() {
        Timer timer = meterRegistry.find("pipeline.step.duration")
                .tag("step", "DECISION")
                .timer();
        
        if (timer == null) {
            return MetricsStatsResponse.LatencyStats.builder()
                    .p50Ms(0)
                    .p95Ms(0)
                    .p99Ms(0)
                    .build();
        }

        return MetricsStatsResponse.LatencyStats.builder()
                .p50Ms(timer.percentile(0.5, TimeUnit.MILLISECONDS))
                .p95Ms(timer.percentile(0.95, TimeUnit.MILLISECONDS))
                .p99Ms(timer.percentile(0.99, TimeUnit.MILLISECONDS))
                .build();
    }

    private Map<String, Long> getQueueDepth() {
        // In monolith variant, there's no queue
        // This will be populated in Kafka/RabbitMQ variants
        Map<String, Long> depth = new HashMap<>();
        depth.put("ingest", 0L);
        depth.put("feature", 0L);
        depth.put("model", 0L);
        depth.put("rule", 0L);
        return depth;
    }

    private Map<String, Long> getDecisionCounts() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("allow", 0L);
        counts.put("review", 0L);
        counts.put("block", 0L);
        
        List<Object[]> results = paymentRepository.countByDecision();
        for (Object[] row : results) {
            Decision decision = (Decision) row[0];
            Long count = (Long) row[1];
            counts.put(decision.name().toLowerCase(), count);
        }
        
        return counts;
    }

    private double getRate(String metricName, int seconds) {
        // Simplified rate calculation
        var counter = meterRegistry.find(metricName).counter();
        if (counter == null) {
            return 0.0;
        }
        return counter.count() / Math.max(1, seconds);
    }
}
