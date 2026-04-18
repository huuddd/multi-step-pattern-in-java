package com.fraud.api.pipeline.steps;

import com.fraud.api.pipeline.PipelineContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Async version of ModelStep that runs scoring in a dedicated thread pool.
 * 
 * Key concepts:
 * - CompletableFuture.supplyAsync() runs task in executor
 * - orTimeout() prevents hanging on slow computations
 * - Metrics track async execution time
 */
@Component
@Slf4j
public class AsyncModelStep {

    private final ThreadPoolExecutor modelScoringExecutor;
    private final MeterRegistry meterRegistry;
    private final long timeoutMs;

    public AsyncModelStep(
            @Qualifier("modelScoringExecutor") ThreadPoolExecutor modelScoringExecutor,
            MeterRegistry meterRegistry) {
        this.modelScoringExecutor = modelScoringExecutor;
        this.meterRegistry = meterRegistry;
        this.timeoutMs = 5000; // 5 second timeout
    }

    /**
     * Execute model scoring asynchronously.
     * 
     * @param context Pipeline context with features
     * @return CompletableFuture that completes with risk score
     */
    public CompletableFuture<BigDecimal> scoreAsync(PipelineContext context) {
        String paymentId = context.getPayment().getPaymentId();
        log.debug("Submitting async model scoring for payment: {}", paymentId);
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        return CompletableFuture.supplyAsync(
                () -> calculateRiskScore(context),
                modelScoringExecutor
        )
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .whenComplete((score, ex) -> {
            // Record metrics
            String status = (ex != null) ? "error" : "success";
            sample.stop(Timer.builder("model.scoring.async.duration")
                    .tag("status", status)
                    .register(meterRegistry));
            
            if (ex != null) {
                log.error("Async model scoring failed for payment {}: {}", 
                        paymentId, ex.getMessage());
            } else {
                log.debug("Async model scoring completed for payment {}: score={}", 
                        paymentId, score);
            }
        });
    }

    /**
     * Calculate risk score using heuristics.
     * This is CPU-bound and runs in the model scoring thread pool.
     */
    private BigDecimal calculateRiskScore(PipelineContext context) {
        Map<String, Object> features = context.getFeatures();
        Long amount = context.getPayment().getAmount();
        
        double score = 0.0;
        
        // High amount increases risk
        double amountPercentile = (double) features.getOrDefault("amount_percentile", 0.0);
        score += amountPercentile * 0.3;
        
        // High card BIN risk
        String binRisk = (String) features.getOrDefault("card_bin_risk_level", "LOW");
        if ("HIGH".equals(binRisk)) {
            score += 0.3;
        }
        
        // High transaction velocity
        int deviceTxCount = (int) features.getOrDefault("device_tx_count_24h", 0);
        if (deviceTxCount > 10) {
            score += 0.2;
        }
        
        // Simulate CPU-bound work (in real system: ML inference)
        simulateCpuWork();
        
        // Random noise to simulate model uncertainty
        score += Math.random() * 0.1;
        
        return BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, score)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Simulate CPU-bound work.
     * In production, this would be ML model inference.
     */
    private void simulateCpuWork() {
        // Simulate 5-20ms of CPU work
        long iterations = 50000 + (long)(Math.random() * 150000);
        double result = 0;
        for (long i = 0; i < iterations; i++) {
            result += Math.sin(i) * Math.cos(i);
        }
        // Prevent JIT from optimizing away
        if (result == Double.MAX_VALUE) {
            log.trace("Impossible");
        }
    }

    /**
     * Get current pool statistics for monitoring.
     */
    public PoolStats getPoolStats() {
        return new PoolStats(
                modelScoringExecutor.getActiveCount(),
                modelScoringExecutor.getPoolSize(),
                modelScoringExecutor.getQueue().size(),
                modelScoringExecutor.getCompletedTaskCount()
        );
    }

    public record PoolStats(
            int activeThreads,
            int poolSize,
            int queueSize,
            long completedTasks
    ) {}
}
