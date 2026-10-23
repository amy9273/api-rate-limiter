package com.enterprise.limiter.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-throughput, lock-free Token Bucket rate limiter.
 *
 * <p>State transitions are strictly non-blocking and atomic, relying on optimistic
 * Compare-And-Swap (CAS) via {@link AtomicReference}. No mutexes, synchronized blocks,
 * or locks are held on the token acquisition hot path, eliminating thread contention
 * even under 100,000+ concurrent requests.
 */
public class TokenBucket {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long capacity;
    private final long refillTokensPerSecond;
    private final NanoClock clock;
    private final AtomicReference<BucketState> stateRef;

    /**
     * Constructs a TokenBucket initialized to full capacity, using the system nano clock.
     *
     * @param capacity maximum burst capacity (must be > 0)
     * @param refillTokensPerSecond tokens replenished per second (must be >= 0)
     */
    public TokenBucket(long capacity, long refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, capacity, NanoClock.system());
    }

    /**
     * Constructs a TokenBucket initialized to full capacity with a custom nano clock.
     *
     * @param capacity maximum burst capacity (must be > 0)
     * @param refillTokensPerSecond tokens replenished per second (must be >= 0)
     * @param clock pluggable nanosecond clock for deterministic testing
     */
    public TokenBucket(long capacity, long refillTokensPerSecond, NanoClock clock) {
        this(capacity, refillTokensPerSecond, capacity, clock);
    }

    /**
     * Constructs a TokenBucket with custom initial tokens and clock.
     *
     * @param capacity maximum burst capacity (must be > 0)
     * @param refillTokensPerSecond tokens replenished per second (must be >= 0)
     * @param initialTokens initial tokens available in bucket (between 0 and capacity)
     * @param clock pluggable nanosecond clock for deterministic testing
     */
    public TokenBucket(long capacity, long refillTokensPerSecond, long initialTokens, NanoClock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (refillTokensPerSecond < 0) {
            throw new IllegalArgumentException("Refill rate cannot be negative: " + refillTokensPerSecond);
        }
        if (initialTokens < 0 || initialTokens > capacity) {
            throw new IllegalArgumentException(
                    "Initial tokens must be between 0 and capacity (" + capacity + "): " + initialTokens);
        }
        this.clock = Objects.requireNonNull(clock, "NanoClock must not be null");
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.stateRef = new AtomicReference<>(new BucketState(initialTokens, clock.nanoTime()));
    }

    /**
     * Attempts to consume a single token.
     *
     * @return true if token was acquired, false if rate limit exceeded
     */
    public boolean tryConsume() {
        return tryConsume(1L);
    }

    /**
     * Attempts to consume the specified number of tokens atomically.
     *
     * @param tokens number of tokens requested (must be > 0)
     * @return true if tokens were acquired, false if rate limit exceeded
     */
    public boolean tryConsume(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens to consume must be greater than 0: " + tokens);
        }
        if (tokens > capacity) {
            return false;
        }

        while (true) {
            long nowNanos = clock.nanoTime();
            BucketState current = stateRef.get();
            BucketState refilled = refill(current, nowNanos);

            if (refilled.availableTokens() < tokens) {
                // Insufficient tokens. Opportunistically update state if refill generated tokens.
                if (refilled != current) {
                    stateRef.compareAndSet(current, refilled);
                }
                return false;
            }

            BucketState updated = new BucketState(
                refilled.availableTokens() - tokens,
                refilled.lastRefillNanos()
            );

            if (stateRef.compareAndSet(current, updated)) {
                return true;
            }
            // CAS failed due to concurrent modification by another thread; retry loop immediately.
        }
    }

    /**
     * Returns the current number of available tokens after accounting for time elapsed.
     *
     * @return available tokens
     */
    public long getAvailableTokens() {
        long nowNanos = clock.nanoTime();
        BucketState current = stateRef.get();
        BucketState refilled = refill(current, nowNanos);
        if (refilled != current) {
            stateRef.compareAndSet(current, refilled);
        }
        return refilled.availableTokens();
    }

    /**
     * Calculates the nanoseconds remaining until at least 1 token is available.
     * If a token is already available, returns 0. If refill rate is 0 and bucket is empty,
     * returns {@link Long#MAX_VALUE}.
     *
     * @return nanoseconds until next token, 0 if available, or Long.MAX_VALUE if infinite
     */
    public long calculateNanosUntilNextToken() {
        return calculateNanosUntilTokens(1L);
    }

    /**
     * Calculates the nanoseconds remaining until the specified number of tokens are available.
     *
     * @param tokensNeeded number of tokens required
     * @return nanoseconds to wait, 0 if already available, or Long.MAX_VALUE if impossible
     */
    public long calculateNanosUntilTokens(long tokensNeeded) {
        if (tokensNeeded <= 0) {
            return 0L;
        }
        if (tokensNeeded > capacity) {
            return Long.MAX_VALUE;
        }

        long nowNanos = clock.nanoTime();
        BucketState current = stateRef.get();
        BucketState refilled = refill(current, nowNanos);

        if (refilled.availableTokens() >= tokensNeeded) {
            return 0L;
        }

        if (refillTokensPerSecond == 0) {
            return Long.MAX_VALUE;
        }

        long deficit = tokensNeeded - refilled.availableTokens();
        long elapsedNanos = Math.max(0L, nowNanos - refilled.lastRefillNanos());
        long nanosPerToken = (long) Math.ceil((deficit * (double) NANOS_PER_SECOND) / refillTokensPerSecond);
        long remainingNanos = nanosPerToken - elapsedNanos;
        return Math.max(0L, remainingNanos);
    }

    /**
     * Calculates the approximate seconds until at least one token is available,
     * suitable for the HTTP {@code Retry-After} response header.
     *
     * @return seconds to wait (minimum 1 second if empty)
     */
    public long calculateRetryAfterSeconds() {
        long nanosRemaining = calculateNanosUntilNextToken();
        if (nanosRemaining == 0L) {
            return 0L;
        }
        if (nanosRemaining == Long.MAX_VALUE) {
            return 60L; // Default backoff if unrefillable
        }
        return (long) Math.ceil(nanosRemaining / (double) NANOS_PER_SECOND);
    }

    /**
     * Internal mathematical refill operation.
     *
     * <p>Carries forward sub-token fractional nanoseconds to prevent drift or token loss
     * over high-frequency access patterns, while guarding against integer overflow.
     */
    private BucketState refill(BucketState current, long nowNanos) {
        long elapsedNanos = nowNanos - current.lastRefillNanos();
        if (elapsedNanos <= 0 || refillTokensPerSecond == 0) {
            return current;
        }

        if (current.availableTokens() >= capacity) {
            return new BucketState(capacity, nowNanos);
        }

        long tokensDeficit = capacity - current.availableTokens();
        long nanosToFull = (tokensDeficit * NANOS_PER_SECOND) / refillTokensPerSecond;

        if (elapsedNanos >= nanosToFull) {
            return new BucketState(capacity, nowNanos);
        }

        long tokensToAdd = (elapsedNanos * refillTokensPerSecond) / NANOS_PER_SECOND;
        if (tokensToAdd > 0) {
            long newTokens = Math.min(capacity, current.availableTokens() + tokensToAdd);
            long nanosConsumed = (tokensToAdd * NANOS_PER_SECOND) / refillTokensPerSecond;
            long newLastRefillNanos = (newTokens >= capacity)
                    ? nowNanos
                    : (current.lastRefillNanos() + nanosConsumed);
            return new BucketState(newTokens, newLastRefillNanos);
        }

        return current;
    }

    public long getCapacity() {
        return capacity;
    }

    public long getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    public BucketState getState() {
        return stateRef.get();
    }
}
