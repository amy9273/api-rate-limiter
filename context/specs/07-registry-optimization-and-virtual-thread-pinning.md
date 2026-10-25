# Spec 07 — Registry Optimization & Virtual Thread Pinning Remediation

---

## 1. Goal & Objectives

Remediate concurrency and resilience issues in `BucketRegistry` identified during the systems audit:
1. **Eliminate Carrier Thread Pinning on Hot Path (LD-03):** Replace `ConcurrentHashMap.compute` bin locking on every request with a lock-free read-through pattern (`get()` fast path + `computeIfAbsent()`), relying on atomic CAS in `ClientBucketEntry.recordAccess()` for zero-lock timestamp sliding.
2. **Memory-Bounded Registry & DoS Protection (PR-01):** Bound the maximum number of concurrent client buckets via configurable `rate-limiter.max-active-buckets`. Trigger emergency evictions or enforce backpressure when registry capacity is reached.
3. **Propagate Concurrency Test Exceptions (CD-05):** Fix `BucketRegistryTest` so worker thread exceptions are captured and asserted via `Future.get()`, and replace weak assertions (`isGreaterThan(0)`) with exact quota conservation checks.
4. **Concurrent Eviction Safety:** Verify race-free interaction between background eviction sweeps and active client traffic.

---

## 2. Requirements & Deliverables

### Deliverable 1: `RateLimiterProperties` Extensions
Add configurable registry limits:
- `private int maxActiveBuckets = 100_000;`
- Validation in `@PostConstruct`: `maxActiveBuckets > 0`.

### Deliverable 2: Lock-Free `BucketRegistry.getOrCreateEntry`
Refactor `getOrCreateEntry`:
```java
ClientBucketEntry entry = registry.get(clientKey);
if (entry == null) {
    checkAndEnforceCapacity();
    entry = registry.computeIfAbsent(clientKey, k -> {
        TokenBucket newBucket = new TokenBucket(capacity, refillRate, clock);
        return new ClientBucketEntry(k, newBucket, nowNanos);
    });
}
entry.recordAccess(nowNanos);
return entry;
```
- If registry size reaches `maxActiveBuckets`:
  1. Trigger immediate `evictExpiredBuckets()`.
  2. If still saturated, reject or shed load and increment `rate_limiter_registry_overflow_total`.

### Deliverable 3: Hardened `BucketRegistryTest`
1. Fix `shouldHandle100ConcurrentThreadsSafely`:
   - Use `List<Future<?>>` with `future.get(10, TimeUnit.SECONDS)`. Zero swallowed exceptions.
   - Assert exact quota accounting across client keys.
2. Add `shouldEnforceMaxBucketCapacityAndEvictOnSaturation()`:
   - Verify registry triggers eviction or sheds load when hitting `maxActiveBuckets`.
3. Add `shouldSafelyHandleConcurrentEvictionAndAccess()`:
   - Concurrently sweep expired buckets while active threads consume from the registry.

---

## 3. Verification Criteria
1. No `synchronized` bin lock contention or carrier pinning on repeated requests for the same client key.
2. Registry bounds heap memory under simulated key-flooding attacks.
3. Concurrency tests propagate thread exceptions and verify quota exactness.
4. `mvn clean test` runs with 100% pass rate.
