# Engineering Best Practices — API Rate Limiter & Reverse Proxy

High-throughput systems programming, concurrency, atomic memory models, and benchmarking.

---

## 1. Concurrency & High-Throughput Memory Models

- **AtomicReference & Compare-And-Swap (CAS):** 
  - Standard locks suspend threads, causing OS context switches (which cost $\sim 1,000\text{–}2,000$ CPU cycles per switch).
  - Use `AtomicReference<BucketState>` with optimistic retry loops (`compareAndSet`). This executes directly as CPU hardware instructions (`LOCK CMPXCHG` on x86-64), taking only $\sim 10\text{–}15$ nanoseconds.
- **Java 21 Virtual Threads:**
  - Leverage `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads allow the proxy to handle 100,000+ simultaneous in-flight connections with negligible memory overhead compared to traditional platform threads (which reserve 1MB stack per thread).
- **Nano-Time Resolution:**
  - Never use `System.currentTimeMillis()` for high-throughput rate limiting. It has poor resolution on Windows/Linux ($\sim 1\text{–}15\text{ ms}$). Always use `System.nanoTime()` for computing elapsed time and token refills.

---

## 2. Mathematical Precision in Token Refill

- **Avoiding Integer Truncation:**
  - Token refill formula:
    $$\text{Refill Tokens} = \frac{\Delta t_{\text{nanos}} \times \text{Refill Rate per Second}}{1,000,000,000}$$
  - Compute tokens as floating/double or carry over remaining nanoseconds in the bucket state so fractional tokens are never lost over high-frequency requests.

---

## 3. Reverse Proxy & Network Streaming Standards

- **Connection Reuse & HTTP Client Pooling:**
  - When proxying traffic to upstream services, utilize an `HttpClient` with persistent HTTP/1.1 and HTTP/2 connection pools. Never open a new TCP socket per proxied request.
- **Streaming Response Bodies:**
  - Stream response bodies asynchronously from upstream back to the client. Never buffer 100MB responses into Java heap memory.

---

## 4. Benchmarking & Profiling Discipline

- **JMH (Java Microbenchmark Harness):**
  - Benchmark the core `tryConsume()` method in isolation using JMH to verify throughput ($> 5,000,000$ ops/sec in-memory) and zero GC allocations.
- **External Load Testing (`wrk` / `k6`):**
  - Run continuous load tests against the running proxy to measure p50, p90, and p99 latency percentiles under 10k, 25k, and 50k concurrent requests.
