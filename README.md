# Token-Bucket API Rate Limiter & Reverse Proxy

[![CI Pipeline](https://github.com/amy9273/api-rate-limiter/actions/workflows/ci.yml/badge.svg)](https://github.com/amy9273/api-rate-limiter/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Virtual Threads](https://img.shields.io/badge/Virtual%20Threads-Project%20Loom-blueviolet.svg)](https://openjdk.org/projects/loom/)
[![Docker](https://img.shields.io/badge/Docker-Multi--Stage%20Alpine-blue.svg)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A Java 21 backend project that combines a token-bucket rate limiter with an HTTP reverse proxy, built using Spring Boot 3.3 and `java.net.http.HttpClient`.

The service applies per-client request quotas, manages token state using atomic compare-and-set operations, forwards permitted requests to an upstream service, and returns standardized rate-limit responses when quotas are exceeded.

### Key Features

- **Token-Bucket Algorithm:** Atomic state updates using `AtomicReference<BucketState>`, configurable burst capacity, refill rates, nanosecond-based refill accounting, and an injectable clock for deterministic testing.
- **Client-Based Rate Limiting:** Resolves client keys using configurable request headers (`X-API-Key`, `X-Tenant-ID`), verified trusted proxy CIDR chains for `X-Forwarded-For`, and socket remote IP fallback.
- **Virtual Threads:** Leverages Java 21 virtual-thread per-task execution (`spring.threads.virtual.enabled=true`) for high-concurrency request dispatching without 1MB OS thread stack overhead.
- **HTTP Reverse Proxy:** Forwards permitted requests to an upstream service (`b2b-saas-billing:8081`), streams request and response bodies directly, strips hop-by-hop/restricted headers, and rewrites redirect locations.
- **RFC-Compliant Rate-Limit Responses:** Injects RFC 6585 traffic control headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`) and returns RFC 7807 / RFC 9457 Problem Details (`application/problem+json`).
- **Bucket Lifecycle & Memory Bounds:** Evicts idle client buckets via a background daemon and enforces configurable maximum active bucket limits to prevent heap exhaustion.
- **Testing & Benchmarking:** Includes 74 automated JUnit 5 tests, multi-threaded concurrency suites capturing asynchronous exceptions, JMH microbenchmarks, and turnkey k6/wrk load-testing scripts.
- **Containerization & CI/CD:** Provides a multi-stage Alpine Dockerfile with non-root security execution and an automated GitHub Actions CI pipeline.

---

## 🏛️ System Architecture

```
                      [ Incoming Client HTTP Traffic ]
                                     │
                                     ▼
      ┌─────────────────────────────────────────────────────────────┐
      │            API Rate Limiter & Reverse Proxy Gateway         │
      │                                                             │
      │  ┌───────────────────────────────────────────────────────┐  │
      │  │        Virtual Thread Dispatcher (Project Loom)       │  │
      │  │        Executors.newVirtualThreadPerTaskExecutor()    │  │
      │  └──────────────────────────┬────────────────────────────┘  │
      │                             ▼                               │
      │  ┌───────────────────────────────────────────────────────┐  │
      │  │             Client Identifier Extraction              │  │
      │  │  1. Header: X-API-Key (sanitized, SHA-256 if >256)    │  │
      │  │  2. Header: X-Tenant-ID                               │  │
      │  │  3. Trusted Proxy X-Forwarded-For (CIDR validated)    │  │
      │  │  4. Socket Remote IP (port stripped)                  │  │
      │  └──────────────────────────┬────────────────────────────┘  │
      │                             ▼                               │
      │  ┌───────────────────────────────────────────────────────┐  │
      │  │             In-Memory Bucket Registry                 │  │
      │  │  - ConcurrentHashMap<String, ClientBucketEntry>       │  │
      │  │  - Lock-free volatile read fast-path + CAS access     │  │
      │  │  - Configurable maxActiveBuckets capacity bounds      │  │
      │  │  - Background Expired Bucket Eviction (Sliding TTL)   │  │
      │  └───────────────┬───────────────────────┬───────────────┘  │
      │                  │                       │                  │
      │    [ Tokens Available ]          [ Limit Exceeded ]         │
      │                  │                       │                  │
      │                  ▼                       ▼                  │
      │  ┌────────────────────────┐    ┌────────────────────────┐   │
      │  │  Reverse Proxy Engine  │    │  HTTP 429 Generator    │   │
      │  │  - Streaming Bodies    │    │  - RFC 6585 Headers    │   │
      │  │  - Restricted Header   │    │  - RFC 7807/9457 JSON  │   │
      │  │    Filtering (Expect)  │    │  - Atomic CAS Snapshot │   │
      │  │  - Location Rewriting  │    │    (remaining = 0)     │   │
      │  │  - Forward to Upstream │    │  - Jackson Serializer  │   │
      │  └───────────────┬────────┘    └────────────────────────┘   │
      │                  │                                          │
      └──────────────────┼──────────────────────────────────────────┘
                         │
                         ▼
        [ Upstream API (e.g. b2b-saas-billing:8081) ]
```

---

## 🚀 Architectural Details & Concurrency Model

- **CAS-Based Token State Updates:**
  Token refills and consumption mutations are performed using lock-free Compare-And-Swap loops on `AtomicReference<BucketState>`. The hot request path avoids coarse synchronized blocks, allowing parallel worker threads to advance independently without mutex deadlocks.
- **Nanosecond-Based Refill Accounting:**
  Calculates token replenishment using discrete nanosecond intervals. Integer arithmetic is bounded against 64-bit signed overflow (`Long.MAX_VALUE / NANOS_PER_SECOND`), preventing illegitimate bucket over-issuance even on astronomical capacities.
- **Synchronized RFC 6585 & RFC 7807 / RFC 9457 Responses:**
  Consumption decisions return an atomic `ConsumptionResult` snapshot. When a request is denied (HTTP 429), `remainingTokens` is strictly pinned to `0`, ensuring response headers (`X-RateLimit-Remaining: 0`) and structured JSON error bodies remain fully consistent under active concurrent refills.
- **Java 21 Virtual Threads (Project Loom):**
  Uses virtual threads (`spring.threads.virtual.enabled=true`) for request dispatching and proxy forwarding. Blocking socket I/O operations unmount virtual threads from carrier platform threads, reducing thread stack overhead compared to traditional 1MB OS thread pools.
- **Registry Concurrency & Capacity Bounds:**
  Registry lookup utilizes a lock-free volatile read fast-path (`ConcurrentHashMap.get()`) and atomic timestamp updates (`AtomicLong.accumulateAndGet()`), avoiding carrier thread pinning on repeated client requests. Heap memory is bounded by an enforced `maxActiveBuckets` limit and periodic daemon eviction sweeps.
- **Trusted Proxy Validation:**
  The `ClientKeyResolver` verifies client socket addresses against configurable trusted proxy CIDR blocks (e.g. `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.1/8`) and traverses `X-Forwarded-For` chains right-to-left to prevent IP spoofing and header tampering.
- **Streaming Reverse Proxy:**
  Streams request and response bodies directly between client and upstream via `java.net.http.HttpClient` without buffering full payloads into memory. Strips hop-by-hop and restricted JDK headers (including `Expect`), encodes query strings safely via `UriComponentsBuilder`, and rewrites upstream redirect `Location` headers.
- **Metrics and Observability:**
  Exposes Prometheus metrics via Spring Boot Actuator at `/actuator/prometheus` (request counters, active bucket counts, registry overflow counters, eviction totals, and proxy latency distribution timers).

---

## 🛠️ Technology Stack

| Layer | Component | Version / Specification |
| :--- | :--- | :--- |
| **Language** | Java LTS | OpenJDK 21 (Temurin) |
| **Threading Model** | Project Loom | Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) |
| **Framework** | Spring Boot | 3.3.4 |
| **Concurrency Primitive** | Atomic CAS | `java.util.concurrent.atomic.AtomicReference` & `AtomicLong` |
| **Serialization** | JSON | Jackson `ObjectMapper` (RFC 7807 / RFC 9457 Problem Details) |
| **Reverse Proxy** | HTTP Client | `java.net.http.HttpClient` (HTTP/1.1 streaming) |
| **Observability** | Metrics | Spring Boot Actuator, Micrometer Prometheus |
| **Testing** | Unit, Concurrency & Integration | JUnit 5, AssertJ, MockMvc, Virtual Thread Pools |
| **Benchmarking** | Micro & Load | JMH 1.37, k6, wrk |
| **Containerization** | Docker | Multi-Stage Alpine Linux, Non-Root (`appuser`) |
| **CI/CD** | Automation | GitHub Actions (`.github/workflows/ci.yml`) |

---

## 🧪 Testing & Verification

The test suite contains **74 automated tests** covering unit behavior, multi-threaded CAS concurrency, HTTP middleware, registry bounds, security sanitization, and end-to-end proxy forwarding:

- **Mathematical Refill & Overflow Protection:** Validates refill accuracy across discrete nanosecond steps via `TestNanoClock`, and asserts that capacities exceeding 10 billion tokens do not trigger 64-bit integer overflow.
- **Atomic Header Synchronization:** Asserts that on HTTP 429 rejection, `X-RateLimit-Remaining` is strictly 0 and exactly matches JSON problem detail fields.
- **Multi-Threaded Concurrency Suites:** 100 concurrent threads compete for quota; tests collect worker `Future<?>` objects and verify completion via `future.get(timeout)` to guarantee all worker-thread exceptions and assertions are propagated to the test runner.
- **Loom Virtual Thread Stress:** Validates concurrent token acquisition and registry lookups across virtual thread pools without thread pinning.
- **Security & Proxy Hardening:** Verifies trusted proxy CIDR traversal, CRLF header stripping, key length hashing, safe URI query encoding, restricted `Expect` header exclusion, and upstream redirect `Location` rewriting.
- **Registry Capacity Bounds:** Verifies emergency eviction sweeps and failure handling when `maxActiveBuckets` is reached.

### Running Tests Locally

```bash
# Windows PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\mvnw.cmd clean test

# Linux / macOS
chmod +x mvnw
./mvnw clean test
```

---

## ⚡ Benchmarking & Performance Suite

### 1. In-Memory Microbenchmarks (JMH)

Measures raw in-memory throughput of `TokenBucket.tryConsume()` under concurrent worker threads:

```bash
# Run JMH benchmark
.\mvnw.cmd test-compile
.\mvnw.cmd exec:java -Dexec.mainClass="com.enterprise.limiter.benchmark.TokenBucketBenchmark" -Dexec.classpathScope="test"
```

> **Note on Benchmark Interpretation:** Microbenchmark throughput for in-memory token acquisition measures raw CAS loop execution in isolation. It is not equivalent to end-to-end HTTP reverse proxy throughput, which includes network socket I/O, servlet filter dispatch, URI parsing, and upstream response latency. Each successful CAS update allocates an immutable `BucketState` record on the heap.

### 2. External Load Testing (`k6` & `wrk`)

Simulate high-concurrency traffic against the running edge proxy:

```bash
# Turnkey PowerShell Runner
.\benchmarks\run-benchmarks.ps1 -Mode k6

# Turnkey Bash Runner
./benchmarks/run-benchmarks.sh k6

# Direct k6 invocation
k6 run benchmarks/k6-rate-limit.js

# High-concurrency wrk pipeline
wrk -t8 -c200 -d30s -s benchmarks/wrk-rate-limit.lua http://localhost:8080/v1/charges
```

---

## ⚠️ Known Limitations & Deployment Considerations

When evaluating this project for production architectures, consider the following trade-offs:

1. **Process-Local State:** Token buckets are held in JVM memory (`ConcurrentHashMap`). If horizontally scaled behind a multi-instance load balancer without sticky sessions, effective rate limits will multiply by the number of active gateway replicas. For shared cluster-wide rate limiting, a distributed backing store (such as Redis with Redis Cell or Lua scripts) is required.
2. **Gateway Ingress Quota Deduction:** Quotas are deducted at the gateway filter boundary prior to upstream invocation. This protects the gateway and downstream systems from retry storms and exhaustion attacks during outages. However, if the upstream service returns 502 Bad Gateway or 504 Gateway Timeout, consumed tokens are not refunded.
3. **Streaming Socket Deadlines:** While virtual threads yield execution during socket blocking I/O, long streaming transfers depend on upstream responsiveness. Downstream streaming should be paired with global gateway read timeouts to prevent long-lived connections from remaining open indefinitely.

---

## 🐳 Production Docker Deployment

A multi-stage Dockerfile is provided, utilizing layer caching and running as an unprivileged system user (`appuser:appgroup`):

```bash
# Build production Docker image
docker build -t api-rate-limiter:latest .

# Run container with upstream linking
docker run -d \
  --name api_rate_limiter \
  -p 8080:8080 \
  -e UPSTREAM_TARGET_URL="http://host.docker.internal:8081" \
  -e RATE_LIMIT_CAPACITY=50 \
  -e RATE_LIMIT_REFILL_PER_SECOND=10 \
  api-rate-limiter:latest
```

### Docker Compose

Launch the Rate Limiter with companion services:

```bash
docker compose up -d
```

---

## 📦 Project Structure

```
api-rate-limiter/
├── .github/
│   └── workflows/
│       └── ci.yml                 # GitHub Actions CI workflow
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── benchmarks/                    # Turnkey benchmarking suite
│   ├── k6-rate-limit.js           # k6 load testing script
│   ├── run-benchmarks.ps1         # PowerShell benchmark runner
│   ├── run-benchmarks.sh          # Bash benchmark runner
│   └── wrk-rate-limit.lua         # wrk high-concurrency Lua script
├── context/                       # Architecture, standards & specs
│   ├── architecture.md
│   ├── code-standards.md
│   ├── engineering-best-practices.md
│   ├── progress-tracker.md
│   └── specs/
├── src/
│   ├── main/
│   │   ├── java/com/enterprise/limiter/
│   │   │   ├── config/
│   │   │   │   ├── ProxyProperties.java
│   │   │   │   └── RateLimiterProperties.java
│   │   │   ├── core/
│   │   │   │   ├── BucketEvictionScheduler.java
│   │   │   │   ├── BucketRegistry.java
│   │   │   │   ├── BucketState.java
│   │   │   │   ├── ClientBucketEntry.java
│   │   │   │   ├── ClientKeyResolver.java
│   │   │   │   ├── ConsumptionResult.java
│   │   │   │   ├── NanoClock.java
│   │   │   │   └── TokenBucket.java
│   │   │   ├── filter/
│   │   │   │   ├── RateLimitErrorResponse.java
│   │   │   │   └── RateLimitingFilter.java
│   │   │   ├── proxy/
│   │   │   │   ├── ReverseProxyController.java
│   │   │   │   └── ReverseProxyService.java
│   │   │   └── RateLimiterApplication.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/enterprise/limiter/
│           ├── audit/
│           │   └── FullAuditVerificationTest.java
│           ├── benchmark/
│           │   └── TokenBucketBenchmark.java
│           ├── core/
│           │   ├── BucketEvictionSchedulerTest.java
│           │   ├── BucketRegistryTest.java
│           │   ├── ClientKeyResolverTest.java
│           │   ├── TestNanoClock.java
│           │   └── TokenBucketTest.java
│           ├── filter/
│           │   ├── RateLimiterIntegrationTest.java
│           │   └── RateLimitingFilterTest.java
│           ├── proxy/
│           │   ├── ReverseProxyIntegrationTest.java
│           │   └── ReverseProxyServiceTest.java
│           └── RateLimiterApplicationTests.java
├── docker-compose.yml
├── Dockerfile
├── mvnw
├── mvnw.cmd
├── pom.xml
└── README.md
```
