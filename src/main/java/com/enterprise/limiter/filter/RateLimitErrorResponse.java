package com.enterprise.limiter.filter;

/**
 * Standard RFC 7807 / RFC 6585 Problem Details representation for rate limit breaches (HTTP 429).
 *
 * @param type URI reference that identifies the problem type
 * @param title short, human-readable summary of the problem
 * @param status HTTP status code (429)
 * @param detail human-readable explanation specific to this occurrence
 * @param instance URI reference that identifies the specific occurrence of the problem
 * @param retryAfterSeconds seconds the client must wait before retrying
 * @param limit maximum burst capacity of the client bucket
 * @param remaining tokens currently available in the client bucket (0 upon rejection)
 * @param resetEpochSeconds Unix epoch seconds when the bucket will be completely replenished
 */
public record RateLimitErrorResponse(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    long retryAfterSeconds,
    long limit,
    long remaining,
    long resetEpochSeconds
) {}
