package com.enterprise.limiter.filter;

import com.enterprise.limiter.config.RateLimiterProperties;
import com.enterprise.limiter.core.BucketRegistry;
import com.enterprise.limiter.core.ClientKeyResolver;
import com.enterprise.limiter.core.TestNanoClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitingFilter & RFC 6585 Specification Tests")
class RateLimitingFilterTest {

    private TestNanoClock clock;
    private RateLimiterProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private BucketRegistry bucketRegistry;
    private ClientKeyResolver keyResolver;
    private ObjectMapper objectMapper;
    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        clock = new TestNanoClock(1_000_000_000L);
        properties = new RateLimiterProperties();
        properties.setDefaultCapacity(5); // Small capacity for fast testing
        properties.setDefaultRefillRate(1);
        properties.setBucketTtl(Duration.ofMinutes(10));

        meterRegistry = new SimpleMeterRegistry();
        bucketRegistry = new BucketRegistry(properties, clock, meterRegistry);
        keyResolver = new ClientKeyResolver();
        objectMapper = new ObjectMapper();

        filter = new RateLimitingFilter(keyResolver, bucketRegistry, objectMapper, meterRegistry);
    }

    @Test
    @DisplayName("Allowed request injects RFC 6585 headers and passes down filter chain")
    void shouldAllowRequestWithinCapacityAndInjectHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/charges");
        request.addHeader("X-API-Key", "sk_test_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(RateLimitingFilter.HEADER_LIMIT)).isEqualTo("5");
        assertThat(response.getHeader(RateLimitingFilter.HEADER_REMAINING)).isEqualTo("4");
        assertThat(response.getHeader(RateLimitingFilter.HEADER_RESET)).isNotNull();
        assertThat(response.getHeader(RateLimitingFilter.HEADER_RETRY_AFTER)).isNull();

        double allowedCount = meterRegistry.get("rate_limiter_requests_total")
                .tag("status", "allowed").counter().count();
        assertThat(allowedCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Exceeding capacity returns HTTP 429 with RFC 6585 headers and RFC 7807 problem details")
    void shouldRejectRequestExceedingCapacityWithRfc7807Payload() throws ServletException, IOException {
        String apiKey = "sk_test_burst";
        FilterChain filterChain = mock(FilterChain.class);

        // Consume all 5 tokens
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/charges");
            req.addHeader("X-API-Key", apiKey);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, filterChain);
            assertThat(res.getStatus()).isEqualTo(200);
        }

        // 6th request: exceeds limit
        MockHttpServletRequest rejectedReq = new MockHttpServletRequest("GET", "/v1/charges");
        rejectedReq.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse rejectedRes = new MockHttpServletResponse();

        filter.doFilter(rejectedReq, rejectedRes, filterChain);

        // Filter chain should NOT be called for rejected request
        verify(filterChain, times(5)).doFilter(any(), any());

        assertThat(rejectedRes.getStatus()).isEqualTo(429);
        assertThat(rejectedRes.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(rejectedRes.getHeader(RateLimitingFilter.HEADER_LIMIT)).isEqualTo("5");
        assertThat(rejectedRes.getHeader(RateLimitingFilter.HEADER_REMAINING)).isEqualTo("0");
        assertThat(rejectedRes.getHeader(RateLimitingFilter.HEADER_RETRY_AFTER)).isNotNull();

        // Verify JSON response body
        String responseBody = rejectedRes.getContentAsString();
        JsonNode json = objectMapper.readTree(responseBody);

        assertThat(json.get("type").asText()).isEqualTo("https://api.gateway.enterprise.com/errors/rate-limit-exceeded");
        assertThat(json.get("title").asText()).isEqualTo("Too Many Requests");
        assertThat(json.get("status").asInt()).isEqualTo(429);
        assertThat(json.get("instance").asText()).isEqualTo("/v1/charges");
        assertThat(json.get("limit").asInt()).isEqualTo(5);
        assertThat(json.get("remaining").asInt()).isEqualTo(0);
        assertThat(json.get("retryAfterSeconds").asLong()).isGreaterThanOrEqualTo(1L);

        double rejectedCount = meterRegistry.get("rate_limiter_requests_total")
                .tag("status", "rejected").counter().count();
        assertThat(rejectedCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Actuator endpoints bypass rate limiting filter completely")
    void shouldBypassActuatorEndpoints() throws ServletException, IOException {
        MockHttpServletRequest healthReq = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse healthRes = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(healthReq, healthRes, filterChain);

        verify(filterChain, times(1)).doFilter(healthReq, healthRes);
        // Headers should not be injected because shouldNotFilter bypassed execution
        assertThat(healthRes.getHeader(RateLimitingFilter.HEADER_LIMIT)).isNull();
        assertThat(bucketRegistry.getActiveBucketCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Favicon requests bypass rate limiting filter")
    void shouldBypassFavicon() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/favicon.ico");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(req, res, filterChain);

        verify(filterChain, times(1)).doFilter(req, res);
        assertThat(res.getHeader(RateLimitingFilter.HEADER_LIMIT)).isNull();
    }
}
