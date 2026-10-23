# Spec 02 — Bucket Registry, Client Key Resolution & TTL Eviction

---

## 1. Goal & Objectives

Implement high-throughput client bucket management, identifier extraction, and memory-bounded TTL eviction:
1. **Client Key Resolution:** Extract client keys with prioritized precedence (`X-API-Key` > `X-Tenant-ID` > `X-Forwarded-For` client IP > remote IP).
2. **Lock-Free Bucket Registry:** Map resolved client keys to `TokenBucket` instances backed by `ConcurrentHashMap` with atomic `computeIfAbsent`.
3. **Sliding Window Activity Tracking & TTL Eviction:** Track last access/activity timestamps per bucket and periodically evict inactive buckets via a background virtual/scheduled daemon to prevent memory exhaustion.
4. **Configuration Properties:** Configurable default capacity, refill rate, TTL duration (e.g., 10 minutes), and cleanup interval (e.g., 60 seconds).
5. **Micrometer Metrics Integration:** Expose gauges and counters for active buckets and purged evictions.
6. **Automated Testing Suite:** Thorough deterministic tests for client key resolution, registry concurrent creation, activity updates, eviction of expired buckets, preservation of active buckets, and multi-threaded stress tests.

---

## 2. Requirements & Deliverables

### Deliverable 1: `ClientKeyResolver`
- Extracts a unique rate-limiting key for incoming requests:
  - Header `X-API-Key` -> `api_key:<value>`
  - Header `X-Tenant-ID` -> `tenant:<value>`
  - Header `X-Forwarded-For` (first IP if comma-separated list) -> `ip:<value>`
  - Remote client IP -> `ip:<value>`
- Sanitizes and trims input, falling back to IP if headers are empty or blank.

### Deliverable 2: `RateLimiterProperties` & Configuration
- `@ConfigurationProperties(prefix = "rate-limiter")`
- Properties:
  - `defaultCapacity`: default burst capacity (e.g., `50`)
  - `defaultRefillRate`: default tokens per second (e.g., `10`)
  - `bucketTtl`: duration before an idle bucket is evicted (default: `10m`)
  - `cleanupInterval`: period between eviction sweep runs (default: `60s`)
  - `cleanupEnabled`: whether background eviction is enabled (default: `true`)

### Deliverable 3: `ClientBucketEntry` & `BucketRegistry`
- `ClientBucketEntry`:
  - Wraps `TokenBucket`, `lastAccessNanos`, and `clientKey`.
  - Atomically records access timestamps on token operations.
- `BucketRegistry`:
  - Internal storage: `ConcurrentHashMap<String, ClientBucketEntry>`.
  - `TokenBucket getOrCreate(String clientKey)`: Atomic `computeIfAbsent` initialization using configured default capacity and refill rate.
  - `TokenBucket getOrCreate(String clientKey, long capacity, long refillRate)`: Custom rule support.
  - `int evictExpiredBuckets(long ttlNanos)`: Sweeps registry, removes entries where `(nowNanos - lastAccessNanos) > ttlNanos`, and returns number of purged buckets.
  - `int getActiveBucketCount()`: Returns active bucket count.
  - Supports pluggable `NanoClock` for deterministic testing of TTL eviction.

### Deliverable 4: `BucketEvictionScheduler`
- Runs scheduled background eviction sweeps at `cleanupInterval`.
- Emits structured debug/info logs when buckets are purged.
- Updates Micrometer metrics:
  - `rate_limiter_active_buckets` (Gauge)
  - `rate_limiter_evictions_total` (Counter)

---

## 3. Verification Criteria
1. `ClientKeyResolver` correctly prioritizes `X-API-Key`, then `X-Tenant-ID`, then `X-Forwarded-For` (first client IP), then remote address.
2. Concurrent requests for the same client key resolve to the identical `TokenBucket` instance with zero race conditions.
3. Accessing a bucket refreshes its activity timestamp, preventing eviction while active.
4. Fast-forwarding virtual clock beyond TTL marks inactive buckets for eviction, while recently active buckets are preserved.
5. Multi-threaded stress tests with 100 concurrent threads creating and consuming tokens across thousands of unique client keys execute cleanly with zero deadlocks and zero memory leaks.
6. `mvn clean test` runs with 100% pass rate.
