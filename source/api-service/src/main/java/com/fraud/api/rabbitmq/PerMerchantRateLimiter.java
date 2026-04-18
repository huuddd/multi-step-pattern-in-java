package com.fraud.api.rabbitmq;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.RateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-merchant rate limiter using Guava RateLimiter.
 * 
 * Each merchant has its own rate limiter with configurable permits per second.
 * This ensures:
 * - Fair resource allocation
 * - Protection against abuse
 * - Tiered service levels (different limits per merchant tier)
 * 
 * Uses Token Bucket algorithm:
 * - Bucket fills at a constant rate (permits per second)
 * - Each request consumes one token
 * - If no tokens available, request is rejected
 */
@Component
@Slf4j
public class PerMerchantRateLimiter {

    private final double defaultPermitsPerSecond;
    private final Map<String, Double> merchantLimits;
    private final MeterRegistry meterRegistry;

    // Cache of rate limiters per merchant
    // Evict after 1 hour of inactivity to prevent memory leak
    private final LoadingCache<String, RateLimiter> rateLimiters;

    // Track rejected requests per merchant
    private final ConcurrentHashMap<String, Long> rejectedCounts = new ConcurrentHashMap<>();

    public PerMerchantRateLimiter(
            @Value("${rate-limit.default-permits-per-second:100}") double defaultPermitsPerSecond,
            MeterRegistry meterRegistry) {
        this.defaultPermitsPerSecond = defaultPermitsPerSecond;
        this.merchantLimits = new ConcurrentHashMap<>();
        this.meterRegistry = meterRegistry;

        this.rateLimiters = CacheBuilder.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .recordStats()
                .build(new CacheLoader<>() {
                    @Override
                    public RateLimiter load(String merchantId) {
                        double permits = getMerchantLimit(merchantId);
                        log.info("Creating rate limiter for merchant {}: {} permits/s", 
                                merchantId, permits);
                        return RateLimiter.create(permits);
                    }
                });

        // Register metrics
        Gauge.builder("rate_limiter.cache.size", rateLimiters, LoadingCache::size)
                .description("Number of cached rate limiters")
                .register(meterRegistry);
    }

    /**
     * Try to acquire a permit for the merchant.
     * Non-blocking - returns immediately.
     * 
     * @param merchantId The merchant ID
     * @return true if permit acquired, false if rate limited
     */
    public boolean tryAcquire(String merchantId) {
        RateLimiter limiter = rateLimiters.getUnchecked(merchantId);
        boolean acquired = limiter.tryAcquire();
        
        if (!acquired) {
            rejectedCounts.merge(merchantId, 1L, Long::sum);
            log.debug("Rate limit exceeded for merchant: {}", merchantId);
        }
        
        return acquired;
    }

    /**
     * Try to acquire a permit with timeout.
     * 
     * @param merchantId The merchant ID
     * @param timeout Maximum time to wait
     * @param unit Time unit
     * @return true if permit acquired within timeout
     */
    public boolean tryAcquire(String merchantId, long timeout, TimeUnit unit) {
        RateLimiter limiter = rateLimiters.getUnchecked(merchantId);
        boolean acquired = limiter.tryAcquire(timeout, unit);
        
        if (!acquired) {
            rejectedCounts.merge(merchantId, 1L, Long::sum);
        }
        
        return acquired;
    }

    /**
     * Acquire a permit, blocking until available.
     * Use with caution - can block indefinitely.
     * 
     * @param merchantId The merchant ID
     * @return Time spent waiting in seconds
     */
    public double acquire(String merchantId) {
        RateLimiter limiter = rateLimiters.getUnchecked(merchantId);
        return limiter.acquire();
    }

    /**
     * Set custom rate limit for a merchant.
     * 
     * @param merchantId The merchant ID
     * @param permitsPerSecond Permits per second
     */
    public void setMerchantLimit(String merchantId, double permitsPerSecond) {
        merchantLimits.put(merchantId, permitsPerSecond);
        
        // Invalidate cached limiter to pick up new limit
        rateLimiters.invalidate(merchantId);
        
        log.info("Updated rate limit for merchant {}: {} permits/s", 
                merchantId, permitsPerSecond);
    }

    /**
     * Get rate limit for merchant.
     * Returns custom limit if set, otherwise default.
     */
    public double getMerchantLimit(String merchantId) {
        return merchantLimits.getOrDefault(merchantId, defaultPermitsPerSecond);
    }

    /**
     * Get current rate (permits acquired per second) for merchant.
     */
    public double getCurrentRate(String merchantId) {
        RateLimiter limiter = rateLimiters.getIfPresent(merchantId);
        return limiter != null ? limiter.getRate() : 0.0;
    }

    /**
     * Get rejected count for merchant.
     */
    public long getRejectedCount(String merchantId) {
        return rejectedCounts.getOrDefault(merchantId, 0L);
    }

    /**
     * Reset rejected count for merchant.
     */
    public void resetRejectedCount(String merchantId) {
        rejectedCounts.remove(merchantId);
    }

    /**
     * Get all merchants with active rate limiters.
     */
    public java.util.Set<String> getActiveMerchants() {
        return rateLimiters.asMap().keySet();
    }

    /**
     * Get cache statistics.
     */
    public String getCacheStats() {
        return rateLimiters.stats().toString();
    }
}
