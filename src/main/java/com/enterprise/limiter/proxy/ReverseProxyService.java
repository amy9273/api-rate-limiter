package com.enterprise.limiter.proxy;

import com.enterprise.limiter.config.ProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Non-blocking, high-throughput reverse proxy service streaming HTTP requests
 * directly to upstream services via Java 21 virtual threads.
 *
 * <p>Hardened features:
 * <ul>
 *   <li>Excludes restricted JDK headers (including {@code Expect}) from forwarding (CD-02).</li>
 *   <li>Safely encodes URIs with special characters and handles malformed inputs (LD-01).</li>
 *   <li>Rewrites upstream {@code Location} redirect headers to relative paths, preventing topology leakage (LD-04).</li>
 *   <li>Serializes RFC 7807 error responses with Jackson {@code ObjectMapper} to eliminate JSON injection (CD-04).</li>
 *   <li>Releases {@code HttpClient} resources gracefully on shutdown via {@code @PreDestroy} (PR-03).</li>
 * </ul>
 */
@Service
public class ReverseProxyService {

    private static final Logger log = LoggerFactory.getLogger(ReverseProxyService.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length",
            "expect"
    );

    private final ProxyProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Timer proxyLatencyTimer;

    public ReverseProxyService(ProxyProperties properties) {
        this(properties, new ObjectMapper(), null);
    }

    public ReverseProxyService(ProxyProperties properties, MeterRegistry meterRegistry) {
        this(properties, new ObjectMapper(), meterRegistry);
    }

    public ReverseProxyService(ProxyProperties properties, HttpClient httpClient, MeterRegistry meterRegistry) {
        this(properties, httpClient, new ObjectMapper(), meterRegistry);
    }

    @Autowired
    public ReverseProxyService(
            ProxyProperties properties,
            ObjectMapper objectMapper,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this(properties, createDefaultHttpClient(properties), objectMapper, meterRegistry);
    }

    public ReverseProxyService(
            ProxyProperties properties,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        if (meterRegistry != null) {
            this.proxyLatencyTimer = Timer.builder("rate_limiter_proxy_latency_seconds")
                    .description("Latency introduced by reverse proxy forwarding to upstream")
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(meterRegistry);
        } else {
            this.proxyLatencyTimer = null;
        }
    }

    private static HttpClient createDefaultHttpClient(ProxyProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @PreDestroy
    public void close() {
        if (httpClient != null) {
            httpClient.close();
            log.info("Closed reverse proxy HttpClient and released carrier resources.");
        }
    }

    /**
     * Proxies the incoming client request to the upstream target service and streams
     * the upstream response back to the client.
     *
     * @param request client HTTP request
     * @param response client HTTP response
     * @throws IOException on streaming or network failure
     */
    public void proxyRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long startTimeNanos = System.nanoTime();

        URI upstreamUri;
        try {
            upstreamUri = buildUpstreamUri(request);
        } catch (IllegalArgumentException e) {
            log.warn("Malformed request URI syntax [{}]: {}", request.getRequestURI(), e.getMessage());
            sendErrorResponse(response, HttpStatus.BAD_REQUEST.value(), "Bad Request",
                    "Malformed request URI syntax", request.getRequestURI());
            return;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(upstreamUri)
                .timeout(properties.getReadTimeout());

        copyRequestHeaders(request, requestBuilder);

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher bodyPublisher = createBodyPublisher(request, method);
        requestBuilder.method(method, bodyPublisher);

        try {
            HttpResponse<InputStream> upstreamResponse = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            response.setStatus(upstreamResponse.statusCode());
            copyResponseHeaders(upstreamResponse, response);

            try (InputStream in = upstreamResponse.body();
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }

        } catch (HttpConnectTimeoutException | ConnectException e) {
            log.error("Failed to connect to upstream server at [{}]: {}", upstreamUri, e.getMessage());
            sendErrorResponse(response, HttpStatus.BAD_GATEWAY.value(), "Bad Gateway",
                    "Upstream server unreachable at " + properties.getTargetUrl(), request.getRequestURI());
        } catch (HttpTimeoutException e) {
            log.error("Upstream server timed out at [{}]: {}", upstreamUri, e.getMessage());
            sendErrorResponse(response, HttpStatus.GATEWAY_TIMEOUT.value(), "Gateway Timeout",
                    "Upstream server timed out after " + properties.getReadTimeout().toSeconds() + "s", request.getRequestURI());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Proxy forwarding interrupted for [{}]", upstreamUri, e);
            sendErrorResponse(response, HttpStatus.BAD_GATEWAY.value(), "Bad Gateway",
                    "Proxy request was interrupted", request.getRequestURI());
        } finally {
            if (proxyLatencyTimer != null) {
                proxyLatencyTimer.record(System.nanoTime() - startTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
    }

    private URI buildUpstreamUri(HttpServletRequest request) {
        String base = properties.getTargetUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base).path(path);
        if (query != null && !query.isBlank()) {
            builder.replaceQuery(query);
        }
        return builder.build().encode().toUri();
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> headerNames = request.getHeaderNames();
        String existingForwardedFor = null;

        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String lowerName = name.toLowerCase(Locale.ROOT);

                if (lowerName.equals("x-forwarded-for")) {
                    existingForwardedFor = request.getHeader(name);
                    continue;
                }

                if (!HOP_BY_HOP_HEADERS.contains(lowerName)) {
                    Enumeration<String> values = request.getHeaders(name);
                    while (values.hasMoreElements()) {
                        builder.header(name, values.nextElement());
                    }
                }
            }
        }

        // Preserve and append verified socket remote IP to X-Forwarded-For
        String clientIp = request.getRemoteAddr();
        String updatedForwardedFor = (existingForwardedFor != null && !existingForwardedFor.isBlank())
                ? existingForwardedFor + ", " + clientIp
                : clientIp;
        builder.header("X-Forwarded-For", updatedForwardedFor);
    }

    private HttpRequest.BodyPublisher createBodyPublisher(HttpServletRequest request, String method) {
        if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)) {
            return HttpRequest.BodyPublishers.noBody();
        }

        return HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void copyResponseHeaders(HttpResponse<?> upstreamResponse, HttpServletResponse response) {
        String base = properties.getTargetUrl();
        String baseNormalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        upstreamResponse.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP_HEADERS.contains(lower)) {
                for (String value : values) {
                    if ("location".equals(lower)) {
                        value = rewriteLocationHeader(value, baseNormalized);
                    }
                    response.addHeader(name, value);
                }
            }
        });
    }

    private String rewriteLocationHeader(String location, String upstreamBase) {
        if (location == null || location.isBlank()) {
            return location;
        }
        // If upstream returned absolute URL pointing to internal upstream, rewrite to relative path
        if (location.startsWith(upstreamBase)) {
            String relative = location.substring(upstreamBase.length());
            return relative.isEmpty() ? "/" : relative;
        }
        return location;
    }

    public record ProxyProblemDetail(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String timestamp
    ) {}

    private void sendErrorResponse(
            HttpServletResponse response,
            int status,
            String title,
            String detail,
            String instance) throws IOException {

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ProxyProblemDetail problem = new ProxyProblemDetail(
                "https://api.gateway.enterprise.com/errors/proxy-error",
                title,
                status,
                detail,
                instance,
                Instant.now().toString()
        );

        objectMapper.writeValue(response.getWriter(), problem);
        response.getWriter().flush();
    }
}
