package com.fraud.api.config;

import com.fraud.api.config.MetricsConfig.InFlightRequestsTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Handles graceful shutdown of thread pools and in-flight requests.
 * 
 * Shutdown sequence:
 * 1. Stop accepting new tasks (executor.shutdown())
 * 2. Wait for in-flight requests to complete
 * 3. Force shutdown if timeout exceeded
 * 4. Log final state
 */
@Component
@Slf4j
public class GracefulShutdownHandler {

    private final ThreadPoolExecutor modelScoringExecutor;
    private final InFlightRequestsTracker inFlightTracker;
    private final MeterRegistry meterRegistry;
    private final int shutdownTimeoutSeconds;
    private final int forceShutdownTimeoutSeconds;

    public GracefulShutdownHandler(
            @Qualifier("modelScoringExecutor") ThreadPoolExecutor modelScoringExecutor,
            InFlightRequestsTracker inFlightTracker,
            MeterRegistry meterRegistry,
            @Value("${shutdown.timeout-seconds:30}") int shutdownTimeoutSeconds,
            @Value("${shutdown.force-timeout-seconds:10}") int forceShutdownTimeoutSeconds) {
        this.modelScoringExecutor = modelScoringExecutor;
        this.inFlightTracker = inFlightTracker;
        this.meterRegistry = meterRegistry;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.forceShutdownTimeoutSeconds = forceShutdownTimeoutSeconds;
    }

    @PreDestroy
    public void shutdown() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║              GRACEFUL SHUTDOWN INITIATED                     ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        
        logCurrentState("BEFORE SHUTDOWN");
        
        // Record shutdown metrics
        recordShutdownMetrics();
        
        // 1. Stop accepting new tasks
        log.info("Step 1: Stopping acceptance of new tasks...");
        modelScoringExecutor.shutdown();
        
        try {
            // 2. Wait for running tasks to complete
            log.info("Step 2: Waiting up to {}s for {} active tasks to complete...",
                    shutdownTimeoutSeconds, modelScoringExecutor.getActiveCount());
            
            boolean terminated = modelScoringExecutor.awaitTermination(
                    shutdownTimeoutSeconds, TimeUnit.SECONDS);
            
            if (terminated) {
                log.info("✓ All tasks completed gracefully");
            } else {
                // 3. Force shutdown if timeout exceeded
                log.warn("⚠ Timeout exceeded, forcing shutdown...");
                
                List<Runnable> droppedTasks = modelScoringExecutor.shutdownNow();
                log.warn("Dropped {} queued tasks", droppedTasks.size());
                
                // Record dropped tasks
                Counter.builder("shutdown.dropped_tasks")
                        .register(meterRegistry)
                        .increment(droppedTasks.size());
                
                // 4. Wait for interrupted tasks
                log.info("Step 3: Waiting {}s for interrupted tasks...", 
                        forceShutdownTimeoutSeconds);
                
                if (!modelScoringExecutor.awaitTermination(
                        forceShutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    log.error("✗ Thread pool did not terminate completely");
                }
            }
            
        } catch (InterruptedException e) {
            log.warn("Shutdown interrupted, forcing immediate shutdown");
            modelScoringExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logCurrentState("AFTER SHUTDOWN");
        
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║              GRACEFUL SHUTDOWN COMPLETE                      ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    private void logCurrentState(String phase) {
        log.info("┌─────────────────────────────────────────────────────────────┐");
        log.info("│ {} State:", phase);
        log.info("│   In-flight requests: {}", inFlightTracker.get());
        log.info("│   Pool size: {}", modelScoringExecutor.getPoolSize());
        log.info("│   Active threads: {}", modelScoringExecutor.getActiveCount());
        log.info("│   Queued tasks: {}", modelScoringExecutor.getQueue().size());
        log.info("│   Completed tasks: {}", modelScoringExecutor.getCompletedTaskCount());
        log.info("│   Is shutdown: {}", modelScoringExecutor.isShutdown());
        log.info("│   Is terminated: {}", modelScoringExecutor.isTerminated());
        log.info("└─────────────────────────────────────────────────────────────┘");
    }

    private void recordShutdownMetrics() {
        try {
            Counter.builder("shutdown.in_flight_at_shutdown")
                    .register(meterRegistry)
                    .increment(inFlightTracker.get());
            
            Counter.builder("shutdown.queued_at_shutdown")
                    .register(meterRegistry)
                    .increment(modelScoringExecutor.getQueue().size());
            
            Counter.builder("shutdown.active_at_shutdown")
                    .register(meterRegistry)
                    .increment(modelScoringExecutor.getActiveCount());
        } catch (Exception e) {
            log.warn("Failed to record shutdown metrics: {}", e.getMessage());
        }
    }
}
