package com.enterprise.limiter.core;

import com.enterprise.limiter.config.RateLimiterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BucketEvictionScheduler Lifecycle & Sweep Tests")
class BucketEvictionSchedulerTest {

    @Test
    @DisplayName("Should run eviction sweep manually and purge expired buckets")
    void shouldPurgeStaleBucketsOnSweep() {
        TestNanoClock clock = new TestNanoClock(1_000_000_000L);
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setBucketTtl(Duration.ofMinutes(10));
        properties.setCleanupInterval(Duration.ofSeconds(60));
        properties.setCleanupEnabled(true);

        BucketRegistry registry = new BucketRegistry(properties, clock, null);
        BucketEvictionScheduler scheduler = new BucketEvictionScheduler(registry, properties);

        registry.getOrCreate("client_stale");
        assertThat(registry.getActiveBucketCount()).isEqualTo(1);

        // Advance 12 minutes
        clock.advanceMinutes(12);

        int evicted = scheduler.runEvictionSweep();
        assertThat(evicted).isEqualTo(1);
        assertThat(registry.getActiveBucketCount()).isEqualTo(0);

        scheduler.stop();
    }
}
