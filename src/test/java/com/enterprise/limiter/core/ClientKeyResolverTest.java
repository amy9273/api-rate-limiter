package com.enterprise.limiter.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientKeyResolver Precedence & Sanitization Tests")
class ClientKeyResolverTest {

    private ClientKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ClientKeyResolver();
    }

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
    @DisplayName("Should extract first client IP from comma-separated X-Forwarded-For header")
    void shouldExtractFirstClientIpFromForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.42, 10.0.0.1, 172.16.0.1");
        request.setRemoteAddr("10.0.0.1");

        String key = resolver.resolveKey(request);

        assertThat(key).isEqualTo("ip:198.51.100.42");
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
    @DisplayName("Should trim whitespace from headers and client IPs")
    void shouldTrimWhitespace() {
        String key = resolver.resolveKey("  key_999  ", " tenant_a ", "  1.2.3.4 , 5.6.7.8 ", " 9.9.9.9 ");
        assertThat(key).isEqualTo("api_key:key_999");

        String keyTenant = resolver.resolveKey(null, "  tenant_b  ", " 1.2.3.4 ", " 9.9.9.9 ");
        assertThat(keyTenant).isEqualTo("tenant:tenant_b");

        String keyIp = resolver.resolveKey("", "  ", "  10.1.2.3 , 10.1.2.4 ", " 9.9.9.9 ");
        assertThat(keyIp).isEqualTo("ip:10.1.2.3");
    }

    @Test
    @DisplayName("Should return ip:unknown when request is null or all identifiers blank")
    void shouldReturnUnknownIpWhenAllAbsent() {
        assertThat(resolver.resolveKey(null)).isEqualTo("ip:unknown");
        assertThat(resolver.resolveKey("  ", "", null, "   ")).isEqualTo("ip:unknown");
    }
}
