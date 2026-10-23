package com.enterprise.limiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the API Rate Limiter and Token Bucket Registry.
 */
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    /**
     * Default burst capacity for client token buckets.
     */
    private long defaultCapacity = 50;

    /**
     * Default tokens replenished per second for client buckets.
     */
    private long defaultRefillRate = 10;

    /**
     * Inactivity duration after which an idle client bucket is eligible for eviction.
     */
    private Duration bucketTtl = Duration.ofMinutes(10);

    /**
     * Period between background eviction sweeps.
     */
    private Duration cleanupInterval = Duration.ofSeconds(60);

    /**
     * Whether background bucket eviction scheduler is enabled.
     */
    private boolean cleanupEnabled = true;

    public RateLimiterProperties() {
    }

    public RateLimiterProperties(long defaultCapacity, long defaultRefillRate, Duration bucketTtl, Duration cleanupInterval, boolean cleanupEnabled) {
        this.defaultCapacity = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
        this.bucketTtl = bucketTtl;
        this.cleanupInterval = cleanupInterval;
        this.cleanupEnabled = cleanupEnabled;
    }

    public long getDefaultCapacity() {
        return defaultCapacity;
    }

    public void setDefaultCapacity(long defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public long getDefaultRefillRate() {
        return defaultRefillRate;
    }

    public void setDefaultRefillRate(long defaultRefillRate) {
        this.defaultRefillRate = defaultRefillRate;
    }

    public Duration getBucketTtl() {
        return bucketTtl;
    }

    public void setBucketTtl(Duration bucketTtl) {
        this.bucketTtl = bucketTtl;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }
}
