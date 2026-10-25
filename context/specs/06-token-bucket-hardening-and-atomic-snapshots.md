# Spec 06 — Token Bucket Hardening, Mathematical Precision & Atomic Snapshots

---

## 1. Goal & Objectives

Address confirmed defects identified during the systems audit of the core token bucket engine:
1. **Eliminate 64-bit Integer Overflow in Refill Math (CD-01):** Prevent arithmetic wrap-around when calculating nanoseconds to full capacity for high-capacity buckets ($> 9.22 \times 10^9$ tokens) or long idle periods.
2. **Atomic Consumption Snapshots (CD-03 Core):** Provide an atomic `ConsumptionResult` record from the CAS mutation loop containing the decision, exact remaining tokens, wait duration, retry-after, and reset epoch timestamp. This eliminates race conditions and state skew between consumption and header generation.
3. **Fail-Fast Configuration Validation:** Validate rate limiter capacity and refill properties at startup to avoid runtime exceptions on worker threads.
4. **Enhanced Concurrency & Virtual Thread Tests:** Introduce tests for concurrent consumption during active token replenishment, verify large capacity boundaries, and ensure all asynchronous exceptions are propagated to the test runner via `Future.get()`.

---

## 2. Requirements & Deliverables

### Deliverable 1: `ConsumptionResult` Record
Create `com.enterprise.limiter.core.ConsumptionResult`:
```java
public record ConsumptionResult(
    boolean allowed,
    long remainingTokens,
    long nanosUntilNextToken,
    long retryAfterSeconds,
    long resetEpochSeconds
) {}
```

### Deliverable 2: `TokenBucket` Enhancements
1. **Overflow-Safe Refill:**
   - In `refill(BucketState current, long nowNanos)`:
     - Guard `tokensDeficit * NANOS_PER_SECOND` against 64-bit integer overflow (`Long.MAX_VALUE / NANOS_PER_SECOND`).
     - Guard `elapsedNanos * refillTokensPerSecond` against overflow before division by `NANOS_PER_SECOND`.
2. **Atomic Snapshot API:**
   - `ConsumptionResult tryConsumeSnapshot(long tokens, long nowEpochSeconds)`
   - Backward-compatible `boolean tryConsume(long tokens)` and `boolean tryConsume()` delegating to `tryConsumeSnapshot(tokens, ...).allowed()`.
3. **Refactored Auxiliary Methods:**
   - Ensure `calculateNanosUntilTokens`, `calculateRetryAfterSeconds`, and `calculateResetEpochSeconds` leverage safe math and state snapshots consistently.

### Deliverable 3: `RateLimiterProperties` Validation
- Validate that `defaultCapacity > 0` and `defaultRefillRate >= 0`. Throw clear `IllegalStateException` or `IllegalArgumentException` on initialization if properties are invalid.

### Deliverable 4: Test Suite Hardening
- Add tests in `TokenBucketTest`:
  - `shouldNotOverflowOnLargeCapacity()` verifying buckets with capacity $10,000,000,000$ do not over-issue tokens on 1 nanosecond advance.
  - `shouldProvideConsistentAtomicSnapshotUnderConcurrency()` verifying snapshot `remainingTokens` never contradicts `allowed`.
  - `shouldHandleConcurrentThreadsWithActiveRefill()` verifying exact token conservation under concurrent threads while refill rate is active ($> 0$).
  - Fix virtual thread test to collect `Future<?>` results and verify zero thrown exceptions via `future.get()`.

---

## 3. Verification Criteria
1. `TokenBucket` accurately handles capacities $\ge 10^{10}$ without arithmetic overflow.
2. `tryConsumeSnapshot` returns consistent state snapshots in a single atomic CAS loop.
3. Concurrency tests run with active token refilling and assert zero exceptions via `Future.get()`.
4. `mvn clean test` compiles and passes with 100% success rate.
