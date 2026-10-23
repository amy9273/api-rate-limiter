# Observability & Developer Experience Standards — API Rate Limiter

Metrics, Terminal Ergonomics, Structured Logging, and Live Dashboarding.

---

## 1. Terminal Ergonomics & Banner

When the rate limiter boots, it must display an informative banner detailing:
- Active Listen Port (e.g. `8080`)
- Upstream Target URL (e.g. `http://localhost:8081` -> `b2b-saas-billing`)
- Virtual Threading Status (`ENABLED (Java 21 Project Loom)`)
- Default Rate Limit Rule (e.g. `Capacity: 50 | Refill: 10 tokens/sec`)

---

## 2. Real-Time Prometheus Metrics (`/actuator/prometheus` or `/metrics`)

Expose standard rate-limiting metrics for Grafana / Prometheus dashboards:
- `rate_limiter_requests_total{status="allowed|rejected"}`: Counter of total requests processed.
- `rate_limiter_active_buckets`: Current number of active tracked client buckets in memory.
- `rate_limiter_proxy_latency_seconds`: Histogram of latency added by the proxy layer.
- `rate_limiter_evictions_total`: Counter of expired ephemeral buckets purged.

---

## 3. High-Throughput Logging Discipline

- **No Per-Request File I/O on Hot Path:** Logging every request synchronously under 50k req/sec destroys performance.
- **Asynchronous Sampling:** Log allowed requests with a 1% sampling rate or trace ID.
- **Immediate Logging on 429 Breaches:** Always log rate limit violations with client identifier and timestamp for security auditing:
  ```text
  WARN [RateLimitEngine] Client [ip=192.168.1.45] exceeded quota. Capacity: 50, Remaining: 0. Returning HTTP 429 (Retry-After: 3s).
  ```
