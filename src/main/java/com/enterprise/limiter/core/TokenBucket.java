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
 *
 * <p>Emits immutable {@link ConsumptionResult} snapshots directly from the atomic
 * CAS state transition to guarantee zero state skew across HTTP status codes and headers.
 */
public class TokenBucket {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_SAFE_TOKENS_FOR_MULTIPLICATION = Long.MAX_VALUE / NANOS_PER_SECOND;

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
     * Attempts to consume a single token, returning true if acquired.
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
        return tryConsumeSnapshot(tokens).allowed();
    }

    /**
     * Attempts to consume a single token and returns an atomic point-in-time snapshot.
     *
     * @return atomic consumption result snapshot
     */
    public ConsumptionResult tryConsumeSnapshot() {
        return tryConsumeSnapshot(1L);
    }

    /**
     * Attempts to consume the specified tokens and returns an atomic point-in-time snapshot,
     * using the current system epoch seconds as the reference for reset time calculation.
     *
     * @param tokens number of tokens requested (must be > 0)
     * @return atomic consumption result snapshot
     */
    public ConsumptionResult tryConsumeSnapshot(long tokens) {
        return tryConsumeSnapshot(tokens, System.currentTimeMillis() / 1000L);
    }

    /**
     * Attempts to consume the specified number of tokens atomically, returning an immutable
     * point-in-time snapshot calculated directly during the atomic CAS loop.
     *
     * <p>Guarantees that {@code remainingTokens}, {@code retryAfterSeconds}, and {@code resetEpochSeconds}
     * are perfectly synchronized with the acquisition decision without thread races or state skew.
     *
     * @param tokens number of tokens requested (must be > 0)
     * @param nowEpochSeconds current epoch time in seconds for reset calculation
     * @return atomic consumption result snapshot
     */
    public ConsumptionResult tryConsumeSnapshot(long tokens, long nowEpochSeconds) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens to consume must be greater than 0: " + tokens);
        }

        while (true) {
            long nowNanos = clock.nanoTime();
            BucketState current = stateRef.get();
            BucketState refilled = refill(current, nowNanos);

            if (tokens > capacity || refilled.availableTokens() < tokens) {
                // Insufficient tokens. Opportunistically publish refilled tokens if state advanced.
                if (refilled != current) {
                    stateRef.compareAndSet(current, refilled);
                }

                long remaining = refilled.availableTokens();
                long nanosUntilNext = calculateNanosUntilTokensInternal(1L, refilled, nowNanos);
                long retryAfter = calculateRetryAfterSecondsInternal(nanosUntilNext);
                long resetEpoch = calculateResetEpochSecondsInternal(remaining, nowEpochSeconds);

                return new ConsumptionResult(false, remaining, nanosUntilNext, retryAfter, resetEpoch);
            }

            BucketState updated = new BucketState(
                refilled.availableTokens() - tokens,
                refilled.lastRefillNanos()
            );

            if (stateRef.compareAndSet(current, updated)) {
                long remaining = updated.availableTokens();
                long resetEpoch = calculateResetEpochSecondsInternal(remaining, nowEpochSeconds);

                return new ConsumptionResult(true, remaining, 0L, 0L, resetEpoch);
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
        long nowNanos = clock.nanoTime();
        BucketState current = stateRef.get();
        BucketState refilled = refill(current, nowNanos);
        return calculateNanosUntilTokensInternal(tokensNeeded, refilled, nowNanos);
    }

    private long calculateNanosUntilTokensInternal(long tokensNeeded, BucketState state, long nowNanos) {
        if (tokensNeeded <= 0) {
            return 0L;
        }
        if (tokensNeeded > capacity || refillTokensPerSecond == 0) {
            return Long.MAX_VALUE;
        }
        if (state.availableTokens() >= tokensNeeded) {
            return 0L;
        }

        long deficit = tokensNeeded - state.availableTokens();
        long elapsedNanos = Math.max(0L, nowNanos - state.lastRefillNanos());

        long nanosForDeficit;
        if (deficit > MAX_SAFE_TOKENS_FOR_MULTIPLICATION) {
            nanosForDeficit = Long.MAX_VALUE;
        } else {
            nanosForDeficit = (long) Math.ceil((deficit * (double) NANOS_PER_SECOND) / refillTokensPerSecond);
        }

        long remainingNanos = nanosForDeficit - elapsedNanos;
        return Math.max(0L, remainingNanos);
    }

    /**
     * Calculates the approximate seconds until at least one token is available,
     * suitable for the HTTP {@code Retry-After} response header.
     *
     * @return seconds to wait (minimum 1 second if empty)
     */
    public long calculateRetryAfterSeconds() {
        return calculateRetryAfterSecondsInternal(calculateNanosUntilNextToken());
    }

    private long calculateRetryAfterSecondsInternal(long nanosRemaining) {
        if (nanosRemaining == 0L) {
            return 0L;
        }
        if (nanosRemaining == Long.MAX_VALUE) {
            return 60L; // Default backoff if unrefillable
        }
        return Math.max(1L, (long) Math.ceil(nanosRemaining / (double) NANOS_PER_SECOND));
    }

    /**
     * Calculates the Unix epoch seconds timestamp when the bucket will be completely
     * replenished back to full capacity.
     *
     * @return epoch seconds timestamp
     */
    public long calculateResetEpochSeconds() {
        return calculateResetEpochSeconds(System.currentTimeMillis() / 1000L);
    }

    /**
     * Calculates the Unix epoch seconds timestamp when the bucket will be completely
     * replenished back to full capacity, based on the provided current epoch seconds.
     *
     * @param nowEpochSeconds reference current epoch timestamp in seconds
     * @return epoch seconds timestamp when fully refilled
     */
    public long calculateResetEpochSeconds(long nowEpochSeconds) {
        return calculateResetEpochSecondsInternal(getAvailableTokens(), nowEpochSeconds);
    }

    private long calculateResetEpochSecondsInternal(long currentTokens, long nowEpochSeconds) {
        if (currentTokens >= capacity || refillTokensPerSecond <= 0) {
            return nowEpochSeconds;
        }
        long deficit = capacity - currentTokens;
        long secondsToFull = (long) Math.ceil((double) deficit / refillTokensPerSecond);
        return nowEpochSeconds + secondsToFull;
    }

    /**
     * Internal mathematical refill operation.
     *
     * <p>Carries forward sub-token fractional nanoseconds to prevent drift or token loss
     * over high-frequency access patterns, while rigorously guarding against 64-bit integer overflow.
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

        // Guard against 64-bit integer overflow: tokensDeficit * NANOS_PER_SECOND
        long nanosToFull;
        if (tokensDeficit > MAX_SAFE_TOKENS_FOR_MULTIPLICATION) {
            nanosToFull = Long.MAX_VALUE;
        } else {
            nanosToFull = (tokensDeficit * NANOS_PER_SECOND) / refillTokensPerSecond;
        }

        if (elapsedNanos >= nanosToFull) {
            return new BucketState(capacity, nowNanos);
        }

        // Guard against overflow when calculating tokensToAdd:
        long tokensToAdd;
        if (elapsedNanos > Long.MAX_VALUE / refillTokensPerSecond) {
            tokensToAdd = capacity;
        } else {
            tokensToAdd = (elapsedNanos * refillTokensPerSecond) / NANOS_PER_SECOND;
        }

        if (tokensToAdd > 0) {
            long newTokens = Math.min(capacity, current.availableTokens() + tokensToAdd);
            long nanosConsumed;
            if (tokensToAdd > MAX_SAFE_TOKENS_FOR_MULTIPLICATION) {
                nanosConsumed = elapsedNanos;
            } else {
                nanosConsumed = (tokensToAdd * NANOS_PER_SECOND) / refillTokensPerSecond;
            }
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
