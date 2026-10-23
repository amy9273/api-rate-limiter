# Spec 04 — High-Throughput Reverse Proxy Forwarding

---

## 1. Goal & Objectives

Implement high-throughput reverse proxy forwarding that transparently proxies permitted requests to upstream target services (such as `b2b-saas-billing` on port 8081) with sub-millisecond overhead:
1. **Upstream Routing & Proxy Engine:** Reverse-proxy all non-Actuator HTTP traffic to the configured upstream server (`proxy.target-url`).
2. **Streaming I/O & Zero Heap Buffering:** Stream request and response bodies directly between client and upstream using Java 21 `java.net.http.HttpClient` with `InputStream.transferTo(...)` to avoid buffering large payloads into memory.
3. **Header Transparency & X-Forwarded-For:** Forward all client headers, query parameters, and HTTP methods, while preserving/appending the client's IP to `X-Forwarded-For`, and omitting hop-by-hop headers (`Connection`, `Keep-Alive`, `Transfer-Encoding`).
4. **Resilience & Timeout Handling:** Configurable connect and request timeouts (`connect-timeout`, `read-timeout`). Return clean `HTTP 504 Gateway Timeout` or `HTTP 502 Bad Gateway` when upstream is unreachable or times out.
5. **Observability & Latency Metrics:** Measure proxy forwarding latency via `rate_limiter_proxy_latency_seconds` (Micrometer `Timer`).
6. **Automated Testing Suite:** Mock/embedded upstream HTTP server tests verifying request body streaming, header forwarding, query parameter preservation, status code propagation, 502/504 error handling, and end-to-end rate limiting integration.

---

## 2. Requirements & Deliverables

### Deliverable 1: `ProxyProperties` Configuration
- Prefix: `proxy` (or `rate-limiter.proxy`)
  - `targetUrl`: Upstream target base URL (default: `http://localhost:8081`)
  - `connectTimeout`: Duration (default: `5s`)
  - `readTimeout`: Duration (default: `10s`)
  - `maxConnections`: HTTP connection pool sizing

### Deliverable 2: `ReverseProxyService`
- Uses a shared singleton `java.net.http.HttpClient` configured with:
  - Connection pool & HTTP/1.1 / HTTP/2 support
  - Virtual thread per-task executor
  - Configured connect timeout
- `void proxyRequest(HttpServletRequest request, HttpServletResponse response)`:
  - Constructs upstream URI preserving request path and query string.
  - Builds `HttpRequest` copying permitted headers (excluding hop-by-hop headers).
  - Appends/sets client IP in `X-Forwarded-For`.
  - Streams request body (`HttpRequest.BodyPublishers.ofInputStream(...)`).
  - Sends request to upstream and receives `HttpResponse<InputStream>`.
  - Sets response status code and response headers from upstream.
  - Streams upstream response body to client: `responseBodyStream.transferTo(response.getOutputStream())`.
  - Records duration in `rate_limiter_proxy_latency_seconds` timer.
  - Catches `HttpConnectTimeoutException` / `ConnectException` &rarr; `HTTP 502 Bad Gateway`.
  - Catches `HttpTimeoutException` &rarr; `HTTP 504 Gateway Timeout`.

### Deliverable 3: `ReverseProxyController` or Proxy Integration
- Maps all non-actuator requests (`/**`) to the `ReverseProxyService`.
- Operates after `RateLimitingFilter`: if quota is available, `RateLimitingFilter` passes request down the filter chain to `ReverseProxyController`.

---

## 3. Verification Criteria
1. Permitted GET, POST, PUT, DELETE requests are forwarded to upstream with headers, bodies, and query params intact.
2. Hop-by-hop headers (`Host`, `Connection`, `Transfer-Encoding`, etc.) are filtered appropriately to prevent HTTP protocol errors.
3. Upstream HTTP status codes (200, 201, 400, 404, 500) and headers are transparently streamed back to the caller.
4. Downstream timeouts trigger clean `HTTP 504 Gateway Timeout`.
5. Downstream connection failures trigger clean `HTTP 502 Bad Gateway`.
6. Rate limiting occurs before proxying: rejected requests (HTTP 429) never reach the upstream server.
7. `mvn clean test` runs with 100% pass rate.
