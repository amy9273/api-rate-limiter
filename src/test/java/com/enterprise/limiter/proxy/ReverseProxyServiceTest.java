package com.enterprise.limiter.proxy;

import com.enterprise.limiter.config.ProxyProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReverseProxyService Upstream Forwarding & Streaming Tests")
class ReverseProxyServiceTest {

    private HttpServer upstreamServer;
    private int upstreamPort;
    private ProxyProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private ReverseProxyService proxyService;

    @BeforeEach
    void setUp() throws IOException {
        upstreamServer = HttpServer.create(new InetSocketAddress(0), 0);
        upstreamPort = upstreamServer.getAddress().getPort();
        upstreamServer.start();

        properties = new ProxyProperties();
        properties.setTargetUrl("http://localhost:" + upstreamPort);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(3));

        meterRegistry = new SimpleMeterRegistry();
        proxyService = new ReverseProxyService(properties, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        if (upstreamServer != null) {
            upstreamServer.stop(0);
        }
    }

    @Test
    @DisplayName("Should transparently proxy GET request and preserve query parameters")
    void shouldProxyGetRequestWithQueryParams() throws IOException {
        AtomicReference<String> receivedQuery = new AtomicReference<>();

        upstreamServer.createContext("/v1/subscriptions", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getQuery());
            String response = "{\"subscriptions\":[\"sub_1\",\"sub_2\"]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/subscriptions");
        request.setQueryString("plan=enterprise&status=active");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        proxyService.proxyRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"subscriptions\":[\"sub_1\",\"sub_2\"]}");
        assertThat(receivedQuery.get()).isEqualTo("plan=enterprise&status=active");
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");

        double latencyCount = meterRegistry.get("rate_limiter_proxy_latency_seconds").timer().count();
        assertThat(latencyCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should proxy POST request body and append client IP to X-Forwarded-For")
    void shouldProxyPostWithBodyAndForwardedFor() throws IOException {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedForwardedFor = new AtomicReference<>();

        upstreamServer.createContext("/v1/charges", exchange -> {
            receivedForwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            receivedBody.set(new String(bodyBytes, StandardCharsets.UTF_8));

            String response = "{\"id\":\"ch_123\",\"amount\":5000}";
            byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/charges");
        request.setContent("{\"customer\":\"cus_abc\",\"amount\":5000}".getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        request.setRemoteAddr("192.168.1.50");

        MockHttpServletResponse response = new MockHttpServletResponse();

        proxyService.proxyRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"ch_123\",\"amount\":5000}");
        assertThat(receivedBody.get()).isEqualTo("{\"customer\":\"cus_abc\",\"amount\":5000}");
        assertThat(receivedForwardedFor.get()).isEqualTo("203.0.113.1, 192.168.1.50");
    }

    @Test
    @DisplayName("Should return HTTP 502 Bad Gateway when upstream server is unreachable")
    void shouldReturn502WhenUpstreamUnreachable() throws IOException {
        // Stop the upstream server to simulate network/host down
        upstreamServer.stop(0);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        proxyService.proxyRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("Bad Gateway");
    }

    @Test
    @DisplayName("Should return HTTP 504 Gateway Timeout when upstream takes longer than read timeout")
    void shouldReturn504WhenUpstreamTimesOut() throws IOException {
        properties.setReadTimeout(Duration.ofMillis(300)); // Fast timeout for test

        upstreamServer.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1000); // Exceeds read timeout
            } catch (InterruptedException ignored) {
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/slow");
        MockHttpServletResponse response = new MockHttpServletResponse();

        proxyService.proxyRequest(request, response);

        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("Gateway Timeout");
    }
}
