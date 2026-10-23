# Project Overview — High-Throughput Token-Bucket API Rate Limiter & Reverse Proxy

Low-Latency, Thread-Safe Traffic Shaper & Gateway OS.

---

## 1. Product & Systems Definition

The **High-Throughput Token-Bucket API Rate Limiter & Reverse Proxy** is a high-performance network edge service built with **Java 21 LTS**, **Virtual Threads (Project Loom)**, and **Lock-Free Atomic Data Structures**. 

Positioned at the front of an API infrastructure (such as the `b2b-saas-billing` engine), it inspects incoming HTTP traffic, enforces granular rate limits per client (IP address, Tenant ID, or API Key), shields upstream microservices from traffic spikes and DDoS attacks, and proxies permitted traffic with sub-millisecond overhead.

---

## 2. Core Engineering Problems Solved

1. **Lock-Free Concurrency at Scale:** Traditional synchronized locks or reentrant mutexes degrade rapidly under 50,000+ concurrent requests due to thread contention. This engine utilizes **Atomic Compare-And-Swap (CAS)** operations (`AtomicReference`, `AtomicLong`) to mutate token reserves with zero lock contention.
2. **Smooth Traffic Shaping (Token Bucket Algorithm):** Unlike brittle fixed-window counters that allow boundary bursts (e.g., 200 requests in 2 seconds across a minute boundary), the token bucket allows controlled burst capacity while strictly enforcing a sustained refill rate.
3. **Standardized HTTP Traffic Control:** Returns standard RFC 6585 headers:
   - `X-RateLimit-Limit`: Maximum burst capacity.
   - `X-RateLimit-Remaining`: Tokens currently available in the client's bucket.
   - `X-RateLimit-Reset`: Unix epoch timestamp when the bucket will be completely replenished.
   - `Retry-After`: Seconds the client must back off when an `HTTP 429 Too Many Requests` is returned.
4. **Memory-Bounded Eviction (Sliding TTL / LRU):** Millions of ephemeral client IPs could exhaust RAM. Ephemeral buckets automatically expire and get purged via a background vacuum routine.
5. **Reverse Proxy Routing:** Transparently forwards authenticated/permitted traffic to downstream upstream servers and streams responses back with minimal latency impact.

---

## 3. Performance & Portfolio Success Criteria

- **Throughput:** Sustains $> 50,000$ requests/sec on standard consumer hardware.
- **Latency Overhead:** Introduces $< 1.0\text{ ms}$ p99 latency overhead on proxied requests.
- **Zero Lock Contention:** Verified via multi-threaded stress tests with 100+ concurrent worker threads.
- **Turnkey Benchmarking:** Includes a load-testing harness (`wrk` / `k6`) to reproduce metrics in 30 seconds.
