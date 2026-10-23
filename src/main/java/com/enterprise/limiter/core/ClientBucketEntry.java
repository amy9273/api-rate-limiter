package com.enterprise.limiter.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry holding the TokenBucket along with atomic sliding-window access telemetry
 * used for TTL-based eviction.
 */
public class ClientBucketEntry {

    private final String clientKey;
    private final TokenBucket bucket;
    private final AtomicLong lastAccessNanos;

    public ClientBucketEntry(String clientKey, TokenBucket bucket, long initialAccessNanos) {
        this.clientKey = Objects.requireNonNull(clientKey, "clientKey must not be null");
        this.bucket = Objects.requireNonNull(bucket, "TokenBucket must not be null");
        this.lastAccessNanos = new AtomicLong(initialAccessNanos);
    }

    /**
     * Records access timestamp in nanoseconds, sliding the eviction window forward.
     *
     * @param nowNanos current timestamp in nanoseconds
     */
    public void recordAccess(long nowNanos) {
        // Optimistically set to nowNanos (or max if out of order)
        lastAccessNanos.accumulateAndGet(nowNanos, Math::max);
    }

    /**
     * Returns the timestamp (in nanoseconds) of the most recent activity on this bucket.
     *
     * @return nanoseconds timestamp
     */
    public long getLastAccessNanos() {
        return lastAccessNanos.get();
    }

    public TokenBucket getBucket() {
        return bucket;
    }

    public String getClientKey() {
        return clientKey;
    }

    /**
     * Checks if the bucket has been inactive for longer than the specified TTL duration.
     *
     * @param nowNanos current timestamp in nanoseconds
     * @param ttlNanos time-to-live threshold in nanoseconds
     * @return true if inactive beyond TTL, false otherwise
     */
    public boolean isExpired(long nowNanos, long ttlNanos) {
        return (nowNanos - lastAccessNanos.get()) > ttlNanos;
    }
}
