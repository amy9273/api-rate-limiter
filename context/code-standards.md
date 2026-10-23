# Code Standards & Quality Gates — API Rate Limiter & Reverse Proxy

Java 21 high-throughput standards, clean systems design, and CI testing requirements.

---

## 1. Java 21 Systems Programming Idioms

- **Immutable State Records:** Represent bucket configurations and snapshots using `record`:
  ```java
  public record RateLimitConfig(
      long capacity,
      long refillTokensPerSecond
  ) {}
  ```
- **Zero Raw Threads:** Never manually create `new Thread(...)`. Always use the Java 21 `ExecutorService` backed by virtual threads.
- **Zero Heavy Locks on Hot Paths:** Avoid `synchronized`, `ReentrantLock`, or `ReadWriteLock` on the token acquisition path. Use `AtomicReference` or `VarHandle`.
- **Zero Heap Allocations on Hot Path:** Avoid creating throwaway objects in the hot token consumption path. Reuse immutable snapshots and avoid boxing/unboxing primitives (`long` instead of `Long`).

---

## 2. Package & Layer Structure

```
src/main/java/com/enterprise/limiter/
├── config/        # Server properties, port mappings, and upstream routes
├── core/          # Lock-free TokenBucket, BucketState, and BucketRegistry
├── filter/        # HTTP interceptor / handler injecting rate limit headers
├── proxy/         # Reverse proxy forwarding engine to upstream service
├── metrics/       # Micrometer metrics (total requests, 429s dropped, latency)
└── RateLimiterApplication.java
```

---

## 3. Standard HTTP Error Payload (RFC 6585)

When a client breaches their token limit, return `HTTP 429 Too Many Requests` with RFC 7807/6585 JSON:

```json
{
  "type": "https://api.gateway.enterprise.com/errors/rate-limit-exceeded",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded. Your bucket capacity is 50 requests with a refill rate of 10 requests/second.",
  "instance": "/v1/charges",
  "retryAfterSeconds": 3,
  "limit": 50,
  "remaining": 0,
  "resetEpochSeconds": 1729678900
}
```

---

## 4. Automated Testing & Benchmark Quality Gates

1. **Deterministic Unit Tests:** Test token refill math, burst capacity, and time calculation with mock clocks (`Clock.fixed(...)` / virtual nanosecond stepping).
2. **Multi-Threaded Concurrency Tests:** Stress test `TokenBucket` with 100 concurrent threads using `CountDownLatch` and `CyclicBarrier` to verify zero race conditions.
3. **End-to-End Proxy Tests:** Spin up an embedded HTTP mock server upstream, proxy requests through the rate limiter, and verify headers and status codes.
