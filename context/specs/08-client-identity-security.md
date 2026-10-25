# Spec 08 — Client Identity Security & Proxy Trust Chains

---

## 1. Goal & Objectives

Remediate security risks and potential DoS vectors in client identity resolution identified during the systems audit:
1. **Remediate Client IP Spoofing via Unauthenticated `X-Forwarded-For` (LD-02):**
   Prevent external clients from bypassing rate limits by supplying arbitrary `X-Forwarded-For` headers. Inspect `X-Forwarded-For` **only** when the direct socket peer (`request.getRemoteAddr()`) matches a configured trusted proxy or load balancer.
2. **Reverse Proxy Trust Chain Traversal:**
   Support both individual IP literals (IPv4/IPv6) and CIDR subnets (e.g., `127.0.0.1`, `::1`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`).
   When `X-Forwarded-For` contains multiple hops, traverse backwards (from right to left) and stop at the first untrusted IP in the chain, ensuring attackers cannot prepend spoofed client IPs.
3. **Key Sanitization, Control Character Stripping & Length Bounding:**
   Enforce length limits on client keys (e.g. max 256 characters) to prevent memory-exhaustion attacks. Strip non-printable and control characters (protecting against log injection CWE-117 and header manipulation). If an API key or tenant ID exceeds the maximum length, hash it with SHA-256 (`api_key:sha256:<hex>`).
4. **IP Literal Validation & Port Stripping:**
   Validate IP address formats without performing DNS lookups. Strip trailing port notations (e.g., `192.168.1.1:54321` or `[::1]:8080`). Fall back safely to `remoteAddr` or `ip:unknown` if malformed.

---

## 2. Requirements & Deliverables

### Deliverable 1: `ProxyProperties` Trusted Proxies Configuration
Extend `ProxyProperties` (prefix `proxy`):
```java
/**
 * List of trusted proxy IP literals or CIDR ranges allowed to supply X-Forwarded-For headers.
 * Defaults to loopback addresses (127.0.0.1, ::1).
 */
private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1"));
```
- Provide getter and setter.
- Support runtime updates and configuration via `application.yml` (`proxy.trusted-proxies`).

### Deliverable 2: `ClientKeyResolver` Hardening
1. **Constructor Injection:**
   - Add `@Autowired` constructor accepting `ProxyProperties`.
   - Maintain a default no-args constructor (defaulting to loopback trust) for ease of testing.
2. **CIDR & IP Matcher:**
   - Parse configured trusted proxy CIDR and IP strings into precompiled subnet structures.
   - Match incoming `remoteAddr` using bitwise subnet masks with IPv4-mapped IPv6 normalization.
   - Enforce IP literal checks to guarantee zero blocking DNS queries.
3. **Right-to-Left `X-Forwarded-For` Evaluation:**
   - If direct socket `remoteAddr` is untrusted, completely ignore `X-Forwarded-For` and use `remoteAddr`.
   - If `remoteAddr` is trusted, split `X-Forwarded-For` by comma, trim, strip ports, and traverse from right to left.
   - Return the first IP that is NOT a trusted proxy. If all hops are trusted, use the leftmost IP.
4. **Key Sanitization:**
   - Remove control characters (`c < 0x20 || c == 0x7F`).
   - Limit raw key string to 256 characters. For inputs exceeding 256 characters, compute deterministic SHA-256 digest hex string.

### Deliverable 3: Test Suite Expansion (`ClientKeyResolverTest`)
1. **Direct Untrusted Client Test:**
   - Direct connection from untrusted IP sending `X-Forwarded-For` -> resolver ignores header and uses `ip:<remoteAddr>`.
2. **Trusted Proxy Single-Hop Test:**
   - Direct connection from `127.0.0.1` sending `X-Forwarded-For: 203.0.113.195` -> resolves `ip:203.0.113.195`.
3. **Multi-Hop Proxy Chain Traversal:**
   - Request with `X-Forwarded-For: 198.51.100.1, 203.0.113.50, 10.0.0.1` where `10.0.0.0/8` is trusted -> resolves `ip:203.0.113.50` (stopping at the first untrusted IP from the right).
4. **CIDR Matching:**
   - Verify `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `::1/128`.
5. **Key Sanitization & Control Characters:**
   - Verify newline/CRLF stripping (`key\r\n` -> `key`).
   - Verify oversized API key hashing (`api_key:sha256:...`).
6. **Port Stripping:**
   - Verify `192.168.1.1:443` -> `192.168.1.1`.
   - Verify `[2001:db8::1]:8080` -> `2001:db8::1`.

---

## 3. Verification Criteria
1. Untrusted clients cannot spoof client IPs or bypass rate limits.
2. Legitimate reverse proxy hops are respected across trusted CIDR subnets.
3. All client key allocations remain strictly bounded in memory under attack.
4. Zero DNS resolution blocking occurs on the hot request path.
5. `mvn clean test` runs with 100% pass rate.
