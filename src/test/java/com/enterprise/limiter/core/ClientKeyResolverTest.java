package com.enterprise.limiter.core;

import com.enterprise.limiter.config.ProxyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientKeyResolver Precedence & Sanitization Tests")
class ClientKeyResolverTest {

    private ClientKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ClientKeyResolver();
    }

    @Nested
    @DisplayName("Precedence & Priority Order")
    class PrecedenceTests {

        @Test
        @DisplayName("Should prioritize X-API-Key above all other headers and IP")
        void shouldPrioritizeApiKey() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", "sk_live_12345");
            request.addHeader("X-Tenant-ID", "tenant-alpha");
            request.addHeader("X-Forwarded-For", "203.0.113.195, 198.51.100.17");
            request.setRemoteAddr("192.168.1.100");

            String key = resolver.resolveKey(request);

            assertThat(key).isEqualTo("api_key:sk_live_12345");
        }

        @Test
        @DisplayName("Should prioritize X-Tenant-ID when X-API-Key is absent or blank")
        void shouldPrioritizeTenantIdWhenApiKeyAbsent() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", "   ");
            request.addHeader("X-Tenant-ID", "org_enterprise_99");
            request.addHeader("X-Forwarded-For", "203.0.113.195");
            request.setRemoteAddr("192.168.1.100");

            String key = resolver.resolveKey(request);

            assertThat(key).isEqualTo("tenant:org_enterprise_99");
        }

        @Test
        @DisplayName("Should fall back to remoteAddr when headers are absent")
        void shouldFallbackToRemoteAddr() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("172.20.10.5");

            String key = resolver.resolveKey(request);

            assertThat(key).isEqualTo("ip:172.20.10.5");
        }

        @Test
        @DisplayName("Should return ip:unknown when request is null or all identifiers blank")
        void shouldReturnUnknownIpWhenAllAbsent() {
            assertThat(resolver.resolveKey(null)).isEqualTo("ip:unknown");
            assertThat(resolver.resolveKey("  ", "", null, "   ")).isEqualTo("ip:unknown");
        }
    }

    @Nested
    @DisplayName("Trusted Proxies & X-Forwarded-For Security (LD-02)")
    class ProxyTrustTests {

        @Test
        @DisplayName("Should extract client IP from X-Forwarded-For when connecting from trusted proxy")
        void shouldExtractFirstClientIpFromForwardedFor() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "198.51.100.42, 10.0.0.1, 172.16.0.1");
            request.setRemoteAddr("10.0.0.1");

            String key = resolver.resolveKey(request);

            assertThat(key).isEqualTo("ip:198.51.100.42");
        }

        @Test
        @DisplayName("Should ignore X-Forwarded-For when direct client socket IP is untrusted")
        void shouldIgnoreForwardedForWhenDirectClientIsUntrusted() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            // Untrusted public IP attempting to spoof internal/random IP
            request.setRemoteAddr("198.51.100.55");
            request.addHeader("X-Forwarded-For", "203.0.113.195");

            String key = resolver.resolveKey(request);

            // Must use direct remoteAddr, completely ignoring spoofed header
            assertThat(key).isEqualTo("ip:198.51.100.55");
        }

        @Test
        @DisplayName("Should traverse multi-hop X-Forwarded-For right-to-left and stop at first untrusted IP")
        void shouldTraverseMultiHopForwardedForRightToLeft() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            // Client spoofed 1.1.1.1, real client was 203.0.113.50, intermediate proxy was 10.2.3.4
            request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.50, 10.2.3.4");
            request.setRemoteAddr("127.0.0.1");

            String key = resolver.resolveKey(request);

            // 10.2.3.4 is trusted (/8). 203.0.113.50 is untrusted -> stops here!
            assertThat(key).isEqualTo("ip:203.0.113.50");
        }

        @Test
        @DisplayName("Should support custom trusted proxies CIDR configuration")
        void shouldSupportCustomTrustedProxiesConfiguration() {
            ProxyProperties customProps = new ProxyProperties();
            customProps.setTrustedProxies(List.of("198.51.100.0/24"));
            ClientKeyResolver customResolver = new ClientKeyResolver(customProps);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("198.51.100.25");
            request.addHeader("X-Forwarded-For", "203.0.113.77");

            String key = customResolver.resolveKey(request);

            assertThat(key).isEqualTo("ip:203.0.113.77");
        }

        @Test
        @DisplayName("Should reject malformed IPs or hostnames in X-Forwarded-For and fall back safely")
        void shouldRejectMalformedIpOrHostname() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "attacker.com, <script>alert(1)</script>");

            String key = resolver.resolveKey(request);

            assertThat(key).isEqualTo("ip:127.0.0.1");
        }
    }

    @Nested
    @DisplayName("Key Sanitization, Port Stripping & Length Bounding")
    class SanitizationTests {

        @Test
        @DisplayName("Should trim whitespace from headers and client IPs")
        void shouldTrimWhitespace() {
            String key = resolver.resolveKey("  key_999  ", " tenant_a ", "  1.2.3.4 , 5.6.7.8 ", " 127.0.0.1 ");
            assertThat(key).isEqualTo("api_key:key_999");

            String keyTenant = resolver.resolveKey(null, "  tenant_b  ", " 1.2.3.4 ", " 127.0.0.1 ");
            assertThat(keyTenant).isEqualTo("tenant:tenant_b");

            String keyIp = resolver.resolveKey("", "  ", "  10.1.2.3 , 10.1.2.4 ", " 127.0.0.1 ");
            assertThat(keyIp).isEqualTo("ip:10.1.2.3");
        }

        @Test
        @DisplayName("Should strip control characters to prevent header and log injection")
        void shouldStripControlCharacters() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", "sk_live_\r\nadmin\t_99");

            String key = resolver.resolveKey(request);

            assertThat(key).isEqualTo("api_key:sk_live_admin_99");
        }

        @Test
        @DisplayName("Should deterministically hash keys exceeding 256 characters with SHA-256")
        void shouldHashOversizedKeysWithSha256() {
            String longApiKey = "k".repeat(300);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", longApiKey);

            String key = resolver.resolveKey(request);

            assertThat(key).startsWith("api_key:sha256:");
            assertThat(key.length()).isEqualTo("api_key:sha256:".length() + 64);

            // Identical long key yields identical hash
            String key2 = resolver.resolveKey(request);
            assertThat(key).isEqualTo(key2);
        }

        @Test
        @DisplayName("Should strip port from IPv4 and bracketed IPv6 socket addresses")
        void shouldStripPorts() {
            MockHttpServletRequest req1 = new MockHttpServletRequest();
            req1.setRemoteAddr("192.168.1.100:8080");
            assertThat(resolver.resolveKey(req1)).isEqualTo("ip:192.168.1.100");

            MockHttpServletRequest req2 = new MockHttpServletRequest();
            req2.setRemoteAddr("[2001:db8::1]:8443");
            assertThat(resolver.resolveKey(req2)).isEqualTo("ip:2001:db8::1");
        }
    }
}
