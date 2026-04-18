package com.fraud.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pool configuration for CPU-bound tasks like model scoring.
 * 
 * Key concepts:
 * - corePoolSize: minimum threads always alive
 * - maxPoolSize: maximum threads when queue is full
 * - queueCapacity: bounded queue for backpressure
 * - rejectionHandler: what to do when pool is exhausted
 */
@Configuration
@Slf4j
public class ThreadPoolConfig {

    @Value("${thread-pool.model-scoring.core-size:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}")
    private int corePoolSize;

    @Value("${thread-pool.model-scoring.max-size:#{T(java.lang.Runtime).getRuntime().availableProcessors() * 2}}")
    private int maxPoolSize;

    @Value("${thread-pool.model-scoring.queue-capacity:500}")
    private int queueCapacity;

    @Value("${thread-pool.model-scoring.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * Thread pool for model scoring (CPU-bound).
     * 
     * Sizing rationale:
     * - corePoolSize = CPU cores (CPU-bound tasks)
     * - maxPoolSize = 2x cores (burst capacity)
     * - queueCapacity = 500 (bounded for backpressure)
     */
    @Bean(name = "modelScoringExecutor")
    public ThreadPoolExecutor modelScoringExecutor(MeterRegistry meterRegistry) {
        log.info("Creating model scoring thread pool: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory("model-scorer"),
                new BackpressureRejectionHandler()
        );

        // Allow core threads to timeout (for graceful scaling down)
        executor.allowCoreThreadTimeOut(true);

        // Register metrics
        ExecutorServiceMetrics.monitor(meterRegistry, executor, "model-scoring-pool");

        return executor;
    }

    /**
     * Creates a ThreadFactory that names threads for easier debugging.
     */
    private ThreadFactory namedThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(prefix + "-" + counter.incrementAndGet());
                thread.setDaemon(false);  // Non-daemon so JVM waits for completion
                
                // Set uncaught exception handler
                thread.setUncaughtExceptionHandler((t, e) -> 
                    log.error("Uncaught exception in thread {}: {}", t.getName(), e.getMessage(), e)
                );
                
                return thread;
            }
        };
    }

    /**
     * Custom rejection handler that provides backpressure signal.
     * Instead of throwing generic RejectedExecutionException,
     * throws a specific exception that can be caught and converted to HTTP 429.
     */
    public static class BackpressureRejectionHandler implements RejectedExecutionHandler {
        
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("Thread pool exhausted! pool={}, queue={}, rejected task",
                    executor.getPoolSize(), executor.getQueue().size());
            
            throw new ThreadPoolExhaustedException(
                    "Model scoring thread pool exhausted. " +
                    "Pool size: " + executor.getPoolSize() + 
                    ", Queue size: " + executor.getQueue().size()
            );
        }
    }

    /**
     * Exception thrown when thread pool cannot accept more tasks.
     * Should be caught and converted to HTTP 429 Too Many Requests.
     */
    public static class ThreadPoolExhaustedException extends RejectedExecutionException {
        public ThreadPoolExhaustedException(String message) {
            super(message);
        }
    }
}
