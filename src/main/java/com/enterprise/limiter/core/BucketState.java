package com.enterprise.limiter.core;

/**
 * Immutable representation of the token bucket's point-in-time state.
 *
 * @param availableTokens currently available tokens in the bucket
 * @param lastRefillNanos timestamp (in nanoseconds) when tokens were last refilled
 */
public record BucketState(
    long availableTokens,
    long lastRefillNanos
) {
    public BucketState {
        if (availableTokens < 0) {
            throw new IllegalArgumentException("availableTokens cannot be negative: " + availableTokens);
        }
    }
}
