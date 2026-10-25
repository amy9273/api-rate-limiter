# Unit 10 Spec — Comprehensive Systems, Security & Concurrency Audit Verification Suite

## Context & Objectives
Following the comprehensive senior systems and security audit of the repository, remediations were modularized across Units 06 through 09. Unit 10 establishes a dedicated, end-to-end regression test suite (`FullAuditVerificationTest`) verifying that all 12 diagnosed defects, risks, and improvements across concurrency, memory safety, HTTP semantics, and network proxying are permanently resolved.

---

## Verified Audit Findings & Scope

| ID | Category | Title | Verification Target |
| :--- | :--- | :--- | :--- |
| **CD-01** | Confirmed Defect | 64-bit integer overflow in refill math | `TokenBucket` with 10B capacity: 0 tokens after 1ns, 10 tokens after 1s |
| **CD-02** | Confirmed Defect | Restricted `Expect` header crash | `ReverseProxyService` excludes `Expect` from forwarded headers |
| **CD-03** | Confirmed Defect | Decoupled atomic consumption & state-skew | `RateLimitingFilter` emits `X-RateLimit-Remaining: 0` synchronized with body on 429 |
| **CD-04** | Confirmed Defect | JSON injection in error responses | `ReverseProxyService` serializes `ProxyProblemDetail` with Jackson `ObjectMapper` |
| **CD-05** | Confirmed Defect | Asynchronous exceptions swallowed in tests | Multi-threaded tests collect `Future<?>` and propagate via `f.get()` |
| **LD-01** | Likely Defect | `URI.create` crash on raw query characters | `UriComponentsBuilder` safely encodes pipe, braces, spaces |
| **LD-02** | Likely Defect | IP spoofing via unvalidated `X-Forwarded-For` | `ClientKeyResolver` verifies trusted CIDR and traverses right-to-left |
| **LD-03** | Likely Defect | Carrier thread pinning on hot path | `BucketRegistry` uses lock-free volatile `get()` fast-path + CAS access updates |
| **LD-04** | Likely Defect | Upstream redirect location header leakage | `ReverseProxyService` rewrites absolute upstream `Location` to relative paths |
| **PR-01** | Potential Risk | Unbounded registry memory exhaustion | `BucketRegistry` enforces `maxActiveBuckets` capacity bounds |
| **PR-03** | Potential Risk | Lifecycle resource leak in `HttpClient` | `ReverseProxyService` cleanly closes client via `@PreDestroy` |
| **PR-04** | Potential Risk | Gateway ingress quota semantics | `RateLimitingFilter` consumes token at ingress before proxy invocation |
| **IM-04** | Improvement | Client key sanitization & length bounding | `ClientKeyResolver` strips CRLF, hashes keys >256 chars with SHA-256 |

---

## Architectural Invariants Maintained
1. **Zero Lock Contention:** Token bucket and registry read paths remain non-blocking (Atomic CAS and volatile reads).
2. **RFC 6585 & 7807 Conformity:** HTTP 429 errors emit strictly synchronized headers and problem detail payloads.
3. **Zero Untrusted Network Leaks:** IP spoofing rejected, redirect locations sanitized, internal hostnames concealed.
4. **Virtual Thread Friendly:** Carrier thread pinning eliminated across the entire request lifecycle.

---

## Deliverables
1. `src/test/java/com/enterprise/limiter/audit/FullAuditVerificationTest.java`: Complete suite of 13 regression tests.
2. `context/specs/10-audit-verification-and-regression-suite.md`: Specification and verification mapping.
3. Updated `context/progress-tracker.md`: Documentation of Unit 10 completion.
