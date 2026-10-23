# Spec 03 — HTTP Middleware & RFC 6585 Rate Limiting Engine

---

## 1. Goal & Objectives

Implement high-throughput HTTP rate-limiting middleware operating within Spring Boot's virtual thread pipeline:
1. **HTTP Rate-Limiting Filter / Interceptor:** Intercept all incoming API requests (excluding Actuator health and Prometheus metrics endpoints) to extract client key, evaluate token consumption, and inject RFC 6585 headers.
2. **Standard RFC 6585 Headers:** Ensure every response contains:
   - `X-RateLimit-Limit`: Bucket capacity.
   - `X-RateLimit-Remaining`: Tokens remaining after consumption.
   - `X-RateLimit-Reset`: Unix epoch timestamp (seconds) when the bucket will be completely replenished.
   - `Retry-After`: Seconds to wait before retrying (present only on `HTTP 429 Too Many Requests`).
3. **Standard RFC 7807 Problem Detail Payload:** Return a standardized JSON response body on 429 breaches with content-type `application/problem+json`.
4. **Structured High-Throughput Logging:** Emit audit logs on 429 rejections with client key, URI, capacity, and retry delay.
5. **Micrometer Metrics:** Record total requests tagged with status: `rate_limiter_requests_total{status="allowed|rejected"}`.
6. **Automated Testing Suite:** MockMvc/WebMvc tests validating allowed requests, header injection, 429 rejections, metrics increments, and endpoint bypasses (`/actuator/**`).

---

## 2. Requirements & Deliverables

### Deliverable 1: `RateLimitErrorResponse` (RFC 7807 / RFC 6585)
- Java 21 record or class for problem details:
  ```java
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
  ```

### Deliverable 2: `RateLimitingFilter`
- Extends `OncePerRequestFilter`.
- Bypasses Actuator endpoints (`/actuator/**`, `/favicon.ico`).
- Uses `ClientKeyResolver` to resolve client key.
- Obtains client bucket from `BucketRegistry`.
- Evaluates `tryConsume(1)`:
  - If **allowed**:
    - Injects `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` into the response headers.
    - Increments `rate_limiter_requests_total{status="allowed"}`.
    - Passes request downstream (`filterChain.doFilter(request, response)`).
  - If **rate limit exceeded (rejected)**:
    - Sets response status `429 Too Many Requests`.
    - Injects `X-RateLimit-Limit`, `X-RateLimit-Remaining: 0`, `X-RateLimit-Reset`, and `Retry-After: <seconds>`.
    - Increments `rate_limiter_requests_total{status="rejected"}`.
    - Emits structured WARN log.
    - Writes `application/problem+json` RFC 7807 payload and terminates request without downstream forwarding.

### Deliverable 3: Metrics & Configuration
- Metric: `rate_limiter_requests_total{status="allowed|rejected"}`.
- Exclude paths configurable via `RateLimiterProperties` (default: `/actuator/**`).

---

## 3. Verification Criteria
1. Allowed requests return `HTTP 200` with headers `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
2. Requests exceeding capacity return `HTTP 429 Too Many Requests` with `Retry-After` header and RFC 7807 JSON body.
3. Actuator endpoints (`/actuator/health`, `/actuator/prometheus`) bypass rate limiting and do not count against quotas.
4. Total allowed and rejected counts are recorded accurately in Prometheus metrics.
5. Multi-request burst tests via MockMvc verify sequential token decrements.
6. `mvn clean test` runs with 100% pass rate.
