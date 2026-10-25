# Spec 09 — HTTP RFC 6585/7807 Semantics & Reverse Proxy Hardening

---

## 1. Goal & Objectives

Remediate HTTP protocol, header synchronization, and reverse proxy resilience defects discovered during the systems audit:
1. **Restricted Header Crash on Proxied `Expect` (CD-02):** Prevent unhandled `IllegalArgumentException: restricted header name: "Expect"` from `HttpRequest.Builder` by adding `"expect"` to `HOP_BY_HOP_HEADERS`.
2. **Header State Skew & RFC 6585 Desynchronization (CD-03):** Eliminate race conditions in `RateLimitingFilter` where `tryConsume()`, `getAvailableTokens()`, and reset calculations read disparate atomic states. Use `TokenBucket.tryConsumeSnapshot()` to ensure `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`, and problem details bodies reflect the exact same atomic CAS snapshot.
3. **JSON Injection in Error Responses (CD-04):** Replace unescaped `String.format` in `ReverseProxyService.sendErrorResponse` with Jackson `ObjectMapper` serialization of structured `ProxyProblemDetail` records.
4. **Safe URI Construction (LD-01):** Replace raw `URI.create` with `UriComponentsBuilder` encoding for path and query strings, preventing unhandled crashes on special characters (`|`, spaces, `{`, `}`) and returning clean HTTP 400 on malformed syntax.
5. **Upstream Redirect Header Leakage (LD-04):** Rewrite upstream `Location` redirect headers to strip internal target base URLs and produce clean relative paths, preventing internal infrastructure topology leakage.
6. **HttpClient Resource Hygiene (PR-03):** Implement `@PreDestroy` lifecycle hook calling `httpClient.close()` to release connection pools and carrier threads gracefully on shutdown.

---

## 2. Requirements & Deliverables

### Deliverable 1: `RateLimitingFilter` Synchronization
- In `doFilterInternal`:
  ```java
  long nowEpochSeconds = Instant.now().getEpochSecond();
  ConsumptionResult snapshot = bucket.tryConsumeSnapshot(1L, nowEpochSeconds);

  response.setHeader(HEADER_LIMIT, String.valueOf(snapshot.capacity()));
  response.setHeader(HEADER_REMAINING, String.valueOf(snapshot.availableTokens()));
  response.setHeader(HEADER_RESET, String.valueOf(snapshot.resetEpochSeconds()));
  ```
- If `!snapshot.consumed()`:
  - Assert `X-RateLimit-Remaining` matches `snapshot.availableTokens()` (guaranteed 0).
  - Use `snapshot.retryAfterSeconds()` for `Retry-After` header and body details.

### Deliverable 2: `ReverseProxyService` Hardening
1. Add `"expect"` to `HOP_BY_HOP_HEADERS`.
2. Safe URI parsing:
   ```java
   UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base).path(path);
   if (query != null && !query.isBlank()) {
       builder.replaceQuery(query);
   }
   return builder.build().encode().toUri();
   ```
   Catch syntax errors and return clean HTTP 400 Bad Request.
3. Error response serialization:
   Use `ObjectMapper.writeValue()` with `ProxyProblemDetail` record.
4. Location header rewriting:
   If response status is 301, 302, 307, or 308, strip upstream target URL prefix from `Location` header to preserve gateway isolation.
5. Resource cleanup:
   Add `@PreDestroy public void close()`.

### Deliverable 3: Expanded Test Suites
1. `RateLimitingFilterTest`:
   - Verify exact consistency of RFC 6585 headers and RFC 7807 error payload under 429 rejection.
   - Verify remaining is exactly 0 when rejected.
2. `ReverseProxyServiceTest`:
   - Verify `Expect: 100-continue` header does not crash proxy.
   - Verify queries with unencoded characters (pipe `|`, space, curly braces) are safely encoded and forwarded.
   - Verify special characters in URI (e.g. `/v1/test"inject`) return valid escaped JSON without injection.
   - Verify upstream redirect `Location: http://localhost:8081/login` is rewritten to relative `/login`.

---

## 3. Verification Criteria
1. Full test suite passes: `mvn clean test`.
2. Zero header skew under high concurrency.
3. Zero crashes on `Expect` headers or special query characters.
4. Clean Jackson-serialized RFC 7807 responses.
