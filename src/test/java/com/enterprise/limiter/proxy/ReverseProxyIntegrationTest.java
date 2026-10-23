package com.enterprise.limiter.proxy;

import com.enterprise.limiter.config.ProxyProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("End-to-End Rate Limiter & Reverse Proxy Integration Tests")
class ReverseProxyIntegrationTest {

    private static HttpServer upstreamMock;
    private static int upstreamPort;
    private static final AtomicInteger upstreamHits = new AtomicInteger(0);

    @BeforeAll
    static void startUpstream() throws IOException {
        upstreamMock = HttpServer.create(new InetSocketAddress(0), 0);
        upstreamPort = upstreamMock.getAddress().getPort();

        upstreamMock.createContext("/v1/saas/invoices", exchange -> {
            upstreamHits.incrementAndGet();
            String json = "{\"invoiceId\":\"inv_999\",\"status\":\"paid\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        upstreamMock.start();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstreamMock != null) {
            upstreamMock.stop(0);
        }
    }

    @DynamicPropertySource
    static void overrideProxyProperties(DynamicPropertyRegistry registry) {
        registry.add("proxy.target-url", () -> "http://localhost:" + upstreamPort);
        registry.add("rate-limiter.default-capacity", () -> "3"); // Capacity of 3 for fast rate-limit test
        registry.add("rate-limiter.default-refill-rate", () -> "1");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Permitted requests are forwarded to upstream and return RFC 6585 headers")
    void shouldProxyPermittedRequestWithHeaders() throws Exception {
        int initialHits = upstreamHits.get();

        mockMvc.perform(get("/v1/saas/invoices")
                        .header("X-API-Key", "sk_proxy_test_client"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(jsonPath("$.invoiceId", is("inv_999")));

        assertThat(upstreamHits.get()).isEqualTo(initialHits + 1);
    }

    @Test
    @DisplayName("Exhausted quotas block requests at proxy layer without reaching upstream")
    void shouldBlockAtProxyLayerWhenQuotaExceeded() throws Exception {
        String apiKey = "sk_blocked_client";
        int initialHits = upstreamHits.get();

        // 3 allowed calls
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/v1/saas/invoices").header("X-API-Key", apiKey))
                    .andExpect(status().isOk());
        }

        assertThat(upstreamHits.get()).isEqualTo(initialHits + 3);

        // 4th call: rejected by RateLimitingFilter (HTTP 429)
        mockMvc.perform(get("/v1/saas/invoices").header("X-API-Key", apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status", is(429)));

        // Upstream hits should NOT have increased for rejected call
        assertThat(upstreamHits.get()).isEqualTo(initialHits + 3);
    }
}
