# Progress Tracker — API Rate Limiter & Reverse Proxy

Current project state, implementation roadmap, and completion status.

---

## Roadmap & Units Overview

| Unit | Title | Target Scope | Status |
| :--- | :--- | :--- | :--- |
| **Unit 01** | Project Scaffold & Lock-Free Token Bucket Core | Maven pom.xml (Java 21), Lock-free TokenBucket (Atomic CAS), Concurrency tests | ✅ Complete |
| **Unit 02** | Bucket Registry, Client Keys & TTL Eviction | ConcurrentHashMap registry, IP/Key resolution, background cleanup scheduler | ✅ Complete |
| **Unit 03** | HTTP Middleware & RFC 6585 Headers Engine | Virtual thread HTTP pipeline, header injection, RFC 7807/6585 429 generator | ⏳ Ready to Start |
| **Unit 04** | High-Throughput Reverse Proxy Forwarding | Non-blocking proxy forwarding to upstream (b2b-saas-billing), streaming bodies | ⚪ Pending |
| **Unit 05** | Production Docker, CI/CD & JMH/Load Benchmark | JMH microbenchmarks, wrk/k6 load testing script, multi-stage Dockerfile | ⚪ Pending |

---

## Phase History & Activity Log

- **2026-10-23**: Initialized project repository context files in `C:\Project\api-rate-limiter/`. Established operating rules, architecture, engineering standards, and specs.
- **2026-10-23**: **Unit 01 Complete**. Implemented lock-free `TokenBucket` with `AtomicReference<BucketState>` (atomic CAS), pluggable `NanoClock` for deterministic tests, Maven wrapper, Spring Boot 3.3.4 with Virtual Threads enabled, comprehensive unit & 100-thread concurrency tests (16/16 passing), GitHub Actions CI/CD workflow, and `README.md`.
- **2026-10-23**: **Unit 02 Complete**. Implemented `ClientKeyResolver` with multi-level header precedence, `ClientBucketEntry` with sliding access tracking, lock-free `BucketRegistry` with `computeIfPresent` TTL eviction and Micrometer gauges/counters, daemon `BucketEvictionScheduler`, and 30/30 automated tests passing.

---

## Open Decisions & Technical Notes

1. **Runtime:** Java 21 LTS with Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
2. **Concurrency:** Atomic CAS (`AtomicReference<BucketState>`) for zero lock contention.
3. **Upstream Pairing:** Proxies seamlessly to `b2b-saas-billing` running on port 8081.
