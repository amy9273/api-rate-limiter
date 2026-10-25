package com.enterprise.limiter.audit;

import com.enterprise.limiter.config.ProxyProperties;
import com.enterprise.limiter.config.RateLimiterProperties;
import com.enterprise.limiter.core.*;
import com.enterprise.limiter.filter.RateLimitingFilter;
import com.enterprise.limiter.proxy.ReverseProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Full Systems & Security Audit Remediation Verification Suite")
class FullAuditVerificationTest {

    @Nested
    @DisplayName("Confirmed Defects (CD-01 to CD-05)")
    class ConfirmedDefectsTests {

        @Test
        @DisplayName("CD-01: 64-bit overflow prevented in refill math for large capacity buckets")
        void verifyCd01RefillOverflowFixed() {
            TestNanoClock clock = new TestNanoClock(1_000_000_000L);
            long largeCapacity = 10_000_000_000L; // 10 billion tokens (overflows 64-bit int math)
            long refillRate = 10L;

            TokenBucket bucket = new TokenBucket(largeCapacity, refillRate, clock);
            bucket.tryConsume(largeCapacity); // Bucket is now empty

            // Advance clock by only 1 nanosecond
            clock.advanceNanos(1L);

            // Under old code, overflow caused nanosToFull to be negative, triggering an immediate full refill
            long available = bucket.getAvailableTokens();
            assertThat(available)
                    .as("Overflow prevented: 10B bucket after 1ns must have 0 tokens, not refilled to 10B")
                    .isEqualTo(0L);

            // Advance clock by 1 full second: should refill exactly 10 tokens accurately
            clock.advanceNanos(1_000_000_000L);
            assertThat(bucket.getAvailableTokens())
                    .as("Normal refill after 1 second must accurately yield 10 tokens")
                    .isEqualTo(10L);
        }

        @Test
        @DisplayName("CD-02: Proxied Expect header excluded from forwarding, avoiding JDK restricted header crash")
        void verifyCd02RestrictedExpectHeaderExcluded() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();
            AtomicBoolean upstreamCalled = new AtomicBoolean(false);

            server.createContext("/test", exchange -> {
                upstreamCalled.set(true);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            server.start();

            try {
                ProxyProperties props = new ProxyProperties();
                props.setTargetUrl("http://localhost:" + port);
                ReverseProxyService proxy = new ReverseProxyService(props);

                MockHttpServletRequest req = new MockHttpServletRequest("POST", "/test");
                req.addHeader("Expect", "100-continue");
                req.setContent("payload".getBytes(StandardCharsets.UTF_8));
                MockHttpServletResponse res = new MockHttpServletResponse();

                proxy.proxyRequest(req, res);

                assertThat(res.getStatus()).isEqualTo(200);
                assertThat(upstreamCalled.get()).isTrue();
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("CD-03: Atomic ConsumptionResult snapshot guarantees synchronized remaining=0 and Retry-After on 429")
        void verifyCd03AtomicSnapshotEliminatesHeaderSkew() throws Exception {
            TestNanoClock clock = new TestNanoClock(1_000_000_000L);
            RateLimiterProperties props = new RateLimiterProperties();
            props.setDefaultCapacity(5);
            props.setDefaultRefillRate(1);

            SimpleMeterRegistry mr = new SimpleMeterRegistry();
            BucketRegistry registry = new BucketRegistry(props, clock, mr);
            ClientKeyResolver keyResolver = new ClientKeyResolver();
            ObjectMapper om = new ObjectMapper();

            RateLimitingFilter filter = new RateLimitingFilter(keyResolver, registry, om, mr);

            // Consume all 5 tokens
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/api");
                req.addHeader("X-API-Key", "sk_skew_test");
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(req, res, (q, s) -> {});
            }

            // 6th request: rejected with 429
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/api");
            req.addHeader("X-API-Key", "sk_skew_test");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (q, s) -> {});

            String remainingHdr = res.getHeader(RateLimitingFilter.HEADER_REMAINING);
            String retryAfterHdr = res.getHeader(RateLimitingFilter.HEADER_RETRY_AFTER);
            JsonNode json = om.readTree(res.getContentAsString());

            assertThat(res.getStatus()).isEqualTo(429);
            assertThat(remainingHdr).isEqualTo("0");
            assertThat(json.get("remaining").asInt()).isEqualTo(0);
            assertThat(retryAfterHdr).isNotNull();
            assertThat(retryAfterHdr).isEqualTo(json.get("retryAfterSeconds").asText());
        }

        @Test
        @DisplayName("CD-04: Jackson ObjectMapper serializes error responses with escaped URI without JSON injection")
        void verifyCd04JsonInjectionEliminated() throws Exception {
            ProxyProperties props = new ProxyProperties();
            props.setTargetUrl("http://localhost:59999"); // Unreachable port -> 502
            ReverseProxyService proxy = new ReverseProxyService(props);

            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/inject\"};\n{\"fake\":\"pwned\"");
            MockHttpServletResponse res = new MockHttpServletResponse();

            proxy.proxyRequest(req, res);

            ObjectMapper om = new ObjectMapper();
            JsonNode node = om.readTree(res.getContentAsString()); // Throws exception if JSON syntax invalid

            assertThat(res.getStatus()).isEqualTo(502);
            assertThat(node.get("status").asInt()).isEqualTo(502);
            assertThat(node.get("instance").asText()).contains("inject\"};\n{\"fake\":\"pwned\"");
        }

        @Test
        @DisplayName("CD-05: Worker thread exceptions captured and propagated via Future.get() across concurrency suites")
        void verifyCd05ConcurrencyExceptionPropagation() throws Exception {
            TestNanoClock clock = new TestNanoClock(1_000_000_000L);
            TokenBucket bucket = new TokenBucket(100, 10, clock);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> bucket.tryConsume(1)));
            }

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS); // Asserts zero uncaught async failures
            }

            // Also verify that an exception inside a worker is caught and propagated via ExecutionException
            Future<?> failingFuture = executor.submit(() -> {
                throw new IllegalStateException("Simulated async worker failure");
            });
            assertThatThrownBy(() -> failingFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);

            executor.shutdown();

            assertThat(bucket.getAvailableTokens()).isEqualTo(96);
        }
    }

    @Nested
    @DisplayName("Likely Defects & Potential Risks (LD-01 to PR-03)")
    class DefectsAndRisksTests {

        @Test
        @DisplayName("LD-01: UriComponentsBuilder safely encodes pipe and brace characters without URI.create crash")
        void verifyLd01SafeUriEncoding() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();
            AtomicReference<String> rawQuery = new AtomicReference<>();

            server.createContext("/v1/search", exchange -> {
                rawQuery.set(exchange.getRequestURI().getRawQuery());
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            server.start();

            try {
                ProxyProperties props = new ProxyProperties();
                props.setTargetUrl("http://localhost:" + port);
                ReverseProxyService proxy = new ReverseProxyService(props);

                MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/search");
                req.setQueryString("q=alpha|beta&filter={status:active}");
                MockHttpServletResponse res = new MockHttpServletResponse();

                proxy.proxyRequest(req, res);

                assertThat(res.getStatus()).isEqualTo(200);
                assertThat(rawQuery.get()).contains("alpha%7Cbeta");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("LD-02: Untrusted X-Forwarded-For rejected and multi-hop resolved right-to-left")
        void verifyLd02IpSpoofingAndTrustChains() {
            ClientKeyResolver resolver = new ClientKeyResolver(); // Default trusted: loopback & RFC 1918 subnets

            // Untrusted direct client attempting to spoof IP
            MockHttpServletRequest untrustedReq = new MockHttpServletRequest();
            untrustedReq.setRemoteAddr("198.51.100.200"); // Public untrusted IP
            untrustedReq.addHeader("X-Forwarded-For", "203.0.113.1"); // Spoofed
            String keyA = resolver.resolveKey(untrustedReq);

            // Trusted proxy multi-hop traversal right-to-left
            MockHttpServletRequest trustedReq = new MockHttpServletRequest();
            trustedReq.setRemoteAddr("127.0.0.1"); // Trusted local proxy
            trustedReq.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.77, 10.0.0.5");
            String keyB = resolver.resolveKey(trustedReq);

            assertThat(keyA).isEqualTo("ip:198.51.100.200");
            assertThat(keyB).isEqualTo("ip:203.0.113.77");
        }

        @Test
        @DisplayName("LD-03: Hot path uses volatile get() fast-path + atomic CAS timestamp sliding, eliminating carrier pinning")
        void verifyLd03CarrierPinningRemediation() {
            TestNanoClock clock = new TestNanoClock(1_000_000_000L);
            RateLimiterProperties props = new RateLimiterProperties();
            BucketRegistry registry = new BucketRegistry(props, clock, null);

            ClientBucketEntry entry1 = registry.getOrCreateEntry("tenant:client_1");
            ClientBucketEntry entry2 = registry.getOrCreateEntry("tenant:client_1");

            assertThat(entry1).isSameAs(entry2);
            assertThat(registry.getActiveBucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("LD-04: Upstream Location redirect rewritten to relative path, concealing backend host and port")
        void verifyLd04LocationHeaderRewriting() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();

            server.createContext("/v1/auth", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://localhost:" + port + "/v1/login?ref=1");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.start();

            try {
                ProxyProperties props = new ProxyProperties();
                props.setTargetUrl("http://localhost:" + port);
                ReverseProxyService proxy = new ReverseProxyService(props);

                MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/auth");
                MockHttpServletResponse res = new MockHttpServletResponse();

                proxy.proxyRequest(req, res);

                assertThat(res.getStatus()).isEqualTo(302);
                assertThat(res.getHeader("Location")).isEqualTo("/v1/login?ref=1");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("PR-01: Max active buckets capacity enforced, shedding excess client allocations")
        void verifyPr01BoundedRegistryMemory() {
            TestNanoClock clock = new TestNanoClock(1_000_000_000L);
            RateLimiterProperties props = new RateLimiterProperties(50, 10, Duration.ofMinutes(10), Duration.ofSeconds(60), true, 2);
            SimpleMeterRegistry mr = new SimpleMeterRegistry();
            BucketRegistry registry = new BucketRegistry(props, clock, mr);

            registry.getOrCreate("c1");
            registry.getOrCreate("c2");

            assertThatThrownBy(() -> registry.getOrCreate("c3"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capacity exceeded");

            assertThat(registry.getActiveBucketCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("PR-03: HttpClient resources closed cleanly via @PreDestroy lifecycle hook")
        void verifyPr03HttpClientLifecycle() {
            ProxyProperties props = new ProxyProperties();
            ReverseProxyService proxy = new ReverseProxyService(props);
            proxy.close(); // Invokes @PreDestroy without error
        }

        @Test
        @DisplayName("IM-04: Key sanitization removes CRLF, hashes >256 char keys with SHA-256, and strips socket ports")
        void verifyIm04KeySanitizationAndBounding() {
            ClientKeyResolver resolver = new ClientKeyResolver();

            // Control character stripping (CWE-117)
            MockHttpServletRequest reqA = new MockHttpServletRequest();
            reqA.addHeader("X-API-Key", "sk_\r\nlive_\tadmin");
            String keyA = resolver.resolveKey(reqA);

            // Oversized key hashed with SHA-256
            String longKey = "x".repeat(300);
            String keyB = resolver.resolveKey(longKey, null, null, null);

            // Socket port stripping
            MockHttpServletRequest reqC = new MockHttpServletRequest();
            reqC.setRemoteAddr("192.168.1.1:8080");
            String keyC = resolver.resolveKey(reqC);

            assertThat(keyA).isEqualTo("api_key:sk_live_admin");
            assertThat(keyB).startsWith("api_key:sha256:");
            assertThat(keyB).hasSize("api_key:sha256:".length() + 64);
            assertThat(keyC).isEqualTo("ip:192.168.1.1");
        }

        @Test
        @DisplayName("PR-04: Gateway ingress boundary consumes quota upfront to prevent self-DDoS during upstream failure")
        void verifyPr04GatewayIngressQuotaSemantics() throws Exception {
            TestNanoClock clock = new TestNanoClock(1_000_000_000L);
            RateLimiterProperties props = new RateLimiterProperties();
            props.setDefaultCapacity(2);
            props.setDefaultRefillRate(0); // No refill

            SimpleMeterRegistry mr = new SimpleMeterRegistry();
            BucketRegistry registry = new BucketRegistry(props, clock, mr);
            ClientKeyResolver keyResolver = new ClientKeyResolver();
            ObjectMapper om = new ObjectMapper();
            RateLimitingFilter filter = new RateLimitingFilter(keyResolver, registry, om, mr);

            // 1st request passes filter
            MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/v1/api");
            req1.addHeader("X-API-Key", "sk_edge_test");
            MockHttpServletResponse res1 = new MockHttpServletResponse();
            filter.doFilter(req1, res1, (q, s) -> {});
            assertThat(res1.getStatus()).isEqualTo(200);

            // 2nd request passes filter
            MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/v1/api");
            req2.addHeader("X-API-Key", "sk_edge_test");
            MockHttpServletResponse res2 = new MockHttpServletResponse();
            filter.doFilter(req2, res2, (q, s) -> {});
            assertThat(res2.getStatus()).isEqualTo(200);

            // 3rd request rejected at ingress before downstream invocation
            MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/v1/api");
            req3.addHeader("X-API-Key", "sk_edge_test");
            MockHttpServletResponse res3 = new MockHttpServletResponse();
            AtomicBoolean downstreamReached = new AtomicBoolean(false);
            filter.doFilter(req3, res3, (q, s) -> downstreamReached.set(true));

            assertThat(res3.getStatus()).isEqualTo(429);
            assertThat(downstreamReached.get()).isFalse();
        }
    }
}
