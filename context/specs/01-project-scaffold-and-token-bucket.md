# Spec 01 — Project Scaffold & Lock-Free Token Bucket Core

---

## 1. Goal & Objectives

Establish the foundation for the High-Throughput API Rate Limiter:
1. Valid Maven build configuration (`pom.xml`) with Java 21 LTS and virtual thread preview/support.
2. The core lock-free `TokenBucket` algorithm implemented using atomic CAS (`AtomicReference<BucketState>`).
3. Nanosecond-resolution time calculations with support for custom/virtual clocks for deterministic testing.
4. Comprehensive unit and multi-threaded stress tests verifying burst behavior, steady refill, and race condition immunity under 100 concurrent threads.

---

## 2. Requirements & Deliverables

### Deliverable 1: `pom.xml`
- Java 21 LTS.
- Dependencies:
  - `org.springframework.boot:spring-boot-starter-web` (configured with Virtual Threads: `spring.threads.virtual.enabled=true`)
  - `org.springframework.boot:spring-boot-starter-actuator`
  - `io.micrometer:micrometer-registry-prometheus`
  - `org.projectlombok:lombok`
  - `org.springframework.boot:spring-boot-starter-test` (JUnit 5, AssertJ, Mockito)

### Deliverable 2: `BucketState` & `TokenBucket`
- `BucketState`: Immutable Java 21 record:
  ```java
  public record BucketState(
      long availableTokens,
      long lastRefillNanos
  ) {}
  ```
- `TokenBucket`:
  - `boolean tryConsume(long tokens)`
  - `long getAvailableTokens()`
  - `long calculateNanosUntilNextToken()`
  - Uses `AtomicReference<BucketState>` with loop CAS retry.
  - Pluggable `NanoClock` interface allowing deterministic testing without sleeping real time.

---

## 3. Verification Criteria
1. Unit tests verify that bursts up to capacity succeed immediately.
2. Unit tests verify that requesting tokens beyond capacity returns `false`.
3. Multi-threaded concurrency test spawns 100 threads competing for a bucket of 1,000 tokens; exactly 1,000 requests succeed and all excess requests fail cleanly with zero exceptions.
4. `mvn clean test` runs with 100% pass rate.
