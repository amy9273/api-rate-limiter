package com.enterprise.limiter.core;

import com.enterprise.limiter.config.RateLimiterProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background daemon that sweeps the {@link BucketRegistry} at configured intervals
 * to purge idle ephemeral client buckets and bound heap memory usage.
 */
@Component
public class BucketEvictionScheduler {

    private static final Logger log = LoggerFactory.getLogger(BucketEvictionScheduler.class);

    private final BucketRegistry registry;
    private final RateLimiterProperties properties;
    private ScheduledExecutorService scheduler;

    @Autowired
    public BucketEvictionScheduler(BucketRegistry registry, RateLimiterProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isCleanupEnabled()) {
            log.info("Bucket eviction background cleanup is disabled by configuration");
            return;
        }

        long intervalSeconds = properties.getCleanupInterval().toSeconds();
        if (intervalSeconds <= 0) {
            intervalSeconds = 60;
        }

        // Dedicated scheduled daemon thread pool for eviction sweep
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bucket-eviction-daemon");
            thread.setDaemon(true);
            return thread;
        });

        scheduler.scheduleWithFixedDelay(
                this::runEvictionSweep,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        log.info("Started background bucket eviction daemon (Interval: {}s, TTL: {})",
                intervalSeconds, properties.getBucketTtl());
    }

    /**
     * Executes a single sweep of the registry, evicting expired entries.
     * Can be invoked directly during tests or manually.
     *
     * @return count of buckets purged
     */
    public int runEvictionSweep() {
        try {
            int evicted = registry.evictExpiredBuckets();
            if (evicted > 0) {
                log.info("Eviction sweep purged {} stale client buckets. Active buckets: {}",
                        evicted, registry.getActiveBucketCount());
            }
            return evicted;
        } catch (Exception e) {
            log.error("Error during scheduled bucket eviction sweep", e);
            return 0;
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.info("Shutting down bucket eviction daemon scheduler...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
