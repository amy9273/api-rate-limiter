package com.enterprise.limiter.core;

/**
 * Immutable point-in-time snapshot representing the outcome of an atomic
 * token consumption attempt within {@link TokenBucket}.
 *
 * <p>Guarantees that HTTP rate-limiting headers and problem details payloads
 * accurately reflect the exact state transition produced by the atomic CAS operation,
 * eliminating state-skew race conditions under high concurrency.
 *
 * @param allowed true if tokens were successfully acquired, false if quota breached
 * @param remainingTokens exact tokens remaining in the bucket immediately after the operation
 * @param nanosUntilNextToken nanoseconds to wait until at least 1 token is available (0 if allowed)
 * @param retryAfterSeconds recommended integer seconds to wait before retrying (0 if allowed)
 * @param resetEpochSeconds Unix epoch timestamp in seconds when the bucket will be completely replenished
 */
public record ConsumptionResult(
    boolean allowed,
    long remainingTokens,
    long nanosUntilNextToken,
    long retryAfterSeconds,
    long resetEpochSeconds
) {
    public ConsumptionResult {
        if (remainingTokens < 0) {
            throw new IllegalArgumentException("remainingTokens cannot be negative: " + remainingTokens);
        }
        if (nanosUntilNextToken < 0) {
            throw new IllegalArgumentException("nanosUntilNextToken cannot be negative: " + nanosUntilNextToken);
        }
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds cannot be negative: " + retryAfterSeconds);
        }
    }
}
