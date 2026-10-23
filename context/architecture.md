# System Architecture & Invariants — API Rate Limiter & Reverse Proxy

---

## 1. System Topology & Flow

```
                      [ Incoming Client HTTP Traffic ]
                                     │
                                     ▼
      ┌─────────────────────────────────────────────────────────────┐
      │         High-Throughput Rate Limiting Reverse Proxy         │
      │                                                             │
      │  ┌───────────────────────────────────────────────────────┐  │
      │  │        Virtual Thread Dispatcher (Project Loom)       │  │
      │  │        Executors.newVirtualThreadPerTaskExecutor()    │  │
      │  └──────────────────────────┬────────────────────────────┘  │
      │                             ▼                               │
      │  ┌───────────────────────────────────────────────────────┐  │
      │  │             Client Identifier Extraction              │  │
      │  │  1. Header: X-API-Key                                 │  │
      │  │  2. Header: X-Tenant-ID                               │  │
      │  │  3. Remote IP (X-Forwarded-For fallback)               │  │
      │  └──────────────────────────┬────────────────────────────┘  │
      │                             ▼                               │
      │  ┌───────────────────────────────────────────────────────┐  │
      │  │           Lock-Free Token Bucket Registry             │  │
      │  │  - ConcurrentHashMap<String, TokenBucket>             │  │
      │  │  - Atomic CAS Refill & Consumption                    │  │
      │  │  - Background Expired Bucket Eviction                 │  │
      │  └───────────────┬───────────────────────┬───────────────┘  │
      │                  │                       │                  │
      │    [ Tokens Available ]          [ Limit Exceeded ]         │
      │                  │                       │                  │
      │                  ▼                       ▼                  │
      │  ┌────────────────────────┐    ┌────────────────────────┐   │
      │  │  Reverse Proxy Engine  │    │  HTTP 429 Generator    │   │
      │  │  - Non-blocking I/O    │    │  - RFC 6585 Headers    │   │
      │  │  - Forward to Upstream │    │  - Retry-After Calc    │   │
      │  └───────────────┬────────┘    └────────────────────────┘   │
      └──────────────────┼──────────────────────────────────────────┘
                         │
                         ▼
        [ Upstream API (e.g. b2b-saas-billing:8080) ]
```

---

## 2. The Token Bucket State Model (Lock-Free CAS)

Each client bucket is represented by an immutable record or atomic state container:

```java
public record BucketState(
    long availableTokens,
    long lastRefillTimestampNanos
) {}
```

### Consumption Loop (Atomic CAS without Locks):
```java
public boolean tryConsume(long tokensNeeded) {
    while (true) {
        BucketState current = stateRef.get();
        BucketState refilled = refill(current);

        if (refilled.availableTokens() < tokensNeeded) {
            return false; // Rate limit exceeded (HTTP 429)
        }

        BucketState updated = new BucketState(
            refilled.availableTokens() - tokensNeeded,
            refilled.lastRefillTimestampNanos()
        );

        // Atomic Compare-And-Swap
        if (stateRef.compareAndSet(current, updated)) {
            return true; // Successfully acquired token
        }
        // If CAS failed due to concurrent update, retry loop immediately
    }
}
```

---

## 3. Architectural Invariants (Non-Negotiable Rules)

1. **Zero Thread Blocking on Token Check:** Never use `synchronized` blocks or `ReentrantLock` around the token consumption path. Mutexes create severe thread convoying under 50k+ req/sec. All state mutation must be atomic CAS.
2. **Standard RFC 6585 Headers:** Every single response (whether permitted or 429 rejected) MUST include:
   - `X-RateLimit-Limit`
   - `X-RateLimit-Remaining`
   - `X-RateLimit-Reset`
   - If 429: `Retry-After: <seconds>`
3. **Bounded Memory Footprint:** The registry must prune stale client buckets (clients inactive for $> 10$ minutes) via an asynchronous scheduled executor to prevent memory exhaustion.
4. **Header Transparency:** Upstream request headers and query parameters must be preserved during proxying, appending the client's verified IP to `X-Forwarded-For`.
5. **Fail-Closed vs Fail-Open Config:** Provide explicit configurable failure behavior (if downstream times out, return `504 Gateway Timeout` cleanly).
