package com.enterprise.limiter.filter;

import com.enterprise.limiter.core.BucketRegistry;
import com.enterprise.limiter.core.ClientBucketEntry;
import com.enterprise.limiter.core.ClientKeyResolver;
import com.enterprise.limiter.core.ConsumptionResult;
import com.enterprise.limiter.core.TokenBucket;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * High-throughput HTTP rate-limiting filter.
 *
 * <p>Enforces granular per-client quotas, injects standardized RFC 6585 response headers
 * ({@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining}, {@code X-RateLimit-Reset}),
 * and emits standard RFC 7807 problem details payloads upon quota breach (HTTP 429).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    public static final String ERROR_TYPE = "https://api.gateway.enterprise.com/errors/rate-limit-exceeded";
    public static final String ERROR_TITLE = "Too Many Requests";

    private final ClientKeyResolver keyResolver;
    private final BucketRegistry bucketRegistry;
    private final ObjectMapper objectMapper;
    private final Counter allowedRequestsCounter;
    private final Counter rejectedRequestsCounter;

    @Autowired
    public RateLimitingFilter(
            ClientKeyResolver keyResolver,
            BucketRegistry bucketRegistry,
            ObjectMapper objectMapper,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.keyResolver = keyResolver;
        this.bucketRegistry = bucketRegistry;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        if (meterRegistry != null) {
            this.allowedRequestsCounter = Counter.builder("rate_limiter_requests_total")
                    .tag("status", "allowed")
                    .description("Counter of total allowed requests")
                    .register(meterRegistry);

            this.rejectedRequestsCounter = Counter.builder("rate_limiter_requests_total")
                    .tag("status", "rejected")
                    .description("Counter of total rejected requests")
                    .register(meterRegistry);
        } else {
            this.allowedRequestsCounter = null;
            this.rejectedRequestsCounter = null;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.startsWith("/actuator") || path.equals("/favicon.ico"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String clientKey = keyResolver.resolveKey(request);
        ClientBucketEntry entry = bucketRegistry.getOrCreateEntry(clientKey);
        TokenBucket bucket = entry.getBucket();

        long nowEpochSeconds = Instant.now().getEpochSecond();
        ConsumptionResult snapshot = bucket.tryConsumeSnapshot(1L, nowEpochSeconds);
        long capacity = bucket.getCapacity();

        // RFC 6585 headers must be present on ALL responses from the synchronized snapshot
        response.setHeader(HEADER_LIMIT, String.valueOf(capacity));
        response.setHeader(HEADER_REMAINING, String.valueOf(snapshot.remainingTokens()));
        response.setHeader(HEADER_RESET, String.valueOf(snapshot.resetEpochSeconds()));

        if (snapshot.allowed()) {
            if (allowedRequestsCounter != null) {
                allowedRequestsCounter.increment();
            }
            filterChain.doFilter(request, response);
        } else {
            if (rejectedRequestsCounter != null) {
                rejectedRequestsCounter.increment();
            }

            long retryAfterSeconds = Math.max(1L, snapshot.retryAfterSeconds());
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            log.warn("Client [{}] exceeded quota on URI [{}]. Capacity: {}, Remaining: {}. Returning HTTP 429 (Retry-After: {}s).",
                    clientKey, request.getRequestURI(), capacity, snapshot.remainingTokens(), retryAfterSeconds);

            String detail = String.format(
                    "Rate limit exceeded. Your bucket capacity is %d requests with a refill rate of %d requests/second.",
                    capacity, bucket.getRefillTokensPerSecond()
            );

            RateLimitErrorResponse error = new RateLimitErrorResponse(
                    ERROR_TYPE,
                    ERROR_TITLE,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    detail,
                    request.getRequestURI(),
                    retryAfterSeconds,
                    capacity,
                    snapshot.remainingTokens(),
                    snapshot.resetEpochSeconds()
            );

            objectMapper.writeValue(response.getWriter(), error);
        }
    }
}
