package com.enterprise.limiter.core;

import com.enterprise.limiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free, high-throughput registry managing client token buckets with sliding-window
 * activity tracking and memory-bounded TTL eviction.
 */
@Component
public class BucketRegistry {

    private static final Logger log = LoggerFactory.getLogger(BucketRegistry.class);

    private final ConcurrentHashMap<String, ClientBucketEntry> registry = new ConcurrentHashMap<>();
    private final RateLimiterProperties properties;
    private final NanoClock clock;
    private final Counter evictionCounter;

    public BucketRegistry(RateLimiterProperties properties) {
        this(properties, NanoClock.system(), null);
    }

    public BucketRegistry(RateLimiterProperties properties, MeterRegistry meterRegistry) {
        this(properties, NanoClock.system(), meterRegistry);
    }

    @Autowired
    public BucketRegistry(RateLimiterProperties properties, NanoClock clock, @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties, "RateLimiterProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "NanoClock must not be null");

        if (meterRegistry != null) {
            Gauge.builder("rate_limiter_active_buckets", this, BucketRegistry::getActiveBucketCount)
                    .description("Current number of active tracked client buckets in memory")
                    .register(meterRegistry);

            this.evictionCounter = Counter.builder("rate_limiter_evictions_total")
                    .description("Total number of expired ephemeral client buckets purged")
                    .register(meterRegistry);
        } else {
            this.evictionCounter = null;
        }
    }

    /**
     * Retrieves or creates a TokenBucket for the given client key using default capacity and refill rate.
     *
     * @param clientKey unique client identifier
     * @return TokenBucket for the client
     */
    public TokenBucket getOrCreate(String clientKey) {
        return getOrCreate(clientKey, properties.getDefaultCapacity(), properties.getDefaultRefillRate());
    }

    /**
     * Retrieves or creates the ClientBucketEntry using default capacity and refill rate.
     *
     * @param clientKey unique client identifier
     * @return ClientBucketEntry holding the bucket and access timestamp
     */
    public ClientBucketEntry getOrCreateEntry(String clientKey) {
        return getOrCreateEntry(clientKey, properties.getDefaultCapacity(), properties.getDefaultRefillRate());
    }

    /**
     * Retrieves or creates a TokenBucket for the given client key with custom capacity and refill rate.
     *
     * @param clientKey unique client identifier
     * @param capacity maximum burst capacity
     * @param refillRate tokens refilled per second
     * @return TokenBucket for the client
     */
    public TokenBucket getOrCreate(String clientKey, long capacity, long refillRate) {
        return getOrCreateEntry(clientKey, capacity, refillRate).getBucket();
    }

    /**
     * Retrieves or creates the ClientBucketEntry, recording current access time.
     *
     * @param clientKey unique client identifier
     * @param capacity maximum burst capacity
     * @param refillRate tokens refilled per second
     * @return ClientBucketEntry holding the bucket and access timestamp
     */
    public ClientBucketEntry getOrCreateEntry(String clientKey, long capacity, long refillRate) {
        Objects.requireNonNull(clientKey, "clientKey must not be null");
        long nowNanos = clock.nanoTime();

        return registry.compute(clientKey, (k, existing) -> {
            if (existing == null) {
                TokenBucket newBucket = new TokenBucket(capacity, refillRate, clock);
                return new ClientBucketEntry(clientKey, newBucket, nowNanos);
            }
            existing.recordAccess(nowNanos);
            return existing;
        });
    }

    /**
     * Atomically consumes tokens for a client key, sliding the eviction TTL window.
     *
     * @param clientKey client identifier
     * @param tokens tokens requested
     * @return true if consumed, false if rate limit breached
     */
    public boolean tryConsume(String clientKey, long tokens) {
        ClientBucketEntry entry = getOrCreateEntry(clientKey, properties.getDefaultCapacity(), properties.getDefaultRefillRate());
        entry.recordAccess(clock.nanoTime());
        return entry.getBucket().tryConsume(tokens);
    }

    /**
     * Atomically consumes a single token for a client key.
     *
     * @param clientKey client identifier
     * @return true if consumed, false if rate limit breached
     */
    public boolean tryConsume(String clientKey) {
        return tryConsume(clientKey, 1L);
    }

    /**
     * Retrieves the bucket entry if present, without creating a new one.
     *
     * @param clientKey client identifier
     * @return entry or null if absent
     */
    public ClientBucketEntry getEntry(String clientKey) {
        return registry.get(clientKey);
    }

    /**
     * Sweeps the registry and evicts all client buckets that have been inactive
     * for longer than the configured TTL duration.
     *
     * @return number of evicted buckets
     */
    public int evictExpiredBuckets() {
        long ttlNanos = properties.getBucketTtl().toNanos();
        return evictExpiredBuckets(ttlNanos);
    }

    /**
     * Sweeps the registry and evicts buckets inactive for longer than the given TTL duration.
     *
     * @param ttl custom TTL duration
     * @return number of evicted buckets
     */
    public int evictExpiredBuckets(Duration ttl) {
        return evictExpiredBuckets(ttl.toNanos());
    }

    /**
     * Evicts buckets that have been inactive for longer than {@code ttlNanos}.
     *
     * <p>Utilizes atomic {@code computeIfPresent} to avoid removing buckets that were
     * refreshed or accessed concurrently during the sweep.
     *
     * @param ttlNanos time-to-live threshold in nanoseconds
     * @return number of evicted buckets
     */
    public int evictExpiredBuckets(long ttlNanos) {
        long nowNanos = clock.nanoTime();
        AtomicInteger evictedCount = new AtomicInteger(0);

        registry.forEach((key, entry) -> {
            if (entry.isExpired(nowNanos, ttlNanos)) {
                registry.computeIfPresent(key, (k, current) -> {
                    if (current.isExpired(nowNanos, ttlNanos)) {
                        evictedCount.incrementAndGet();
                        return null; // Atomically removes entry
                    }
                    return current; // Retain if access timestamp refreshed concurrently
                });
            }
        });

        int evicted = evictedCount.get();
        if (evicted > 0) {
            log.debug("Evicted {} expired client buckets (active remaining: {})", evicted, registry.size());
            if (evictionCounter != null) {
                evictionCounter.increment(evicted);
            }
        }

        return evicted;
    }

    /**
     * Returns the current number of tracked client buckets.
     *
     * @return active bucket count
     */
    public int getActiveBucketCount() {
        return registry.size();
    }

    /**
     * Clears all client buckets from the registry.
     */
    public void clear() {
        registry.clear();
    }
}
