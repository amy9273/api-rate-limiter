import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics for rate limiter observability
const allowedRequests = new Counter('rate_limiter_allowed_requests');
const rejectedRequests = new Counter('rate_limiter_rejected_requests');
const proxyLatency = new Trend('rate_limiter_proxy_latency', true);
const successRate = new Rate('rate_limiter_success_rate');

export const options = {
  scenarios: {
    // High-concurrency burst and sustained throughput simulation
    burst_and_sustained: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '5s', target: 50 },    // Warm-up to 50 VUs
        { duration: '10s', target: 200 },  // Ramp to 200 concurrent clients
        { duration: '10s', target: 500 },  // Peak traffic spike (500 VUs)
        { duration: '5s', target: 0 },     // Cool-down
      ],
      gracefulRampDown: '2s',
    },
  },
  thresholds: {
    // Latency success criteria: p95 < 5ms, p99 < 10ms for edge proxy overhead
    http_req_duration: ['p(95)<5', 'p(99)<10'],
    rate_limiter_success_rate: ['rate>0.50'],
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const CLIENT_KEYS = Array.from({ length: 50 }, (_, i) => `sk_live_tenant_${i + 1}`);

export default function () {
  // Rotate through 50 simulated tenants to exercise multi-bucket concurrency
  const randomKey = CLIENT_KEYS[Math.floor(Math.random() * CLIENT_KEYS.length)];
  const randomIp = `192.168.1.${(Math.floor(Math.random() * 250) + 1)}`;

  const params = {
    headers: {
      'X-API-Key': randomKey,
      'X-Forwarded-For': randomIp,
      'Accept': 'application/json',
    },
  };

  const res = http.get(`${BASE_URL}/v1/subscriptions?plan=enterprise`, params);
  proxyLatency.add(res.timings.duration);

  // Validate RFC 6585 compliance
  const hasLimitHeader = res.headers['X-Ratelimit-Limit'] !== undefined || res.headers['X-RateLimit-Limit'] !== undefined;
  const hasRemainingHeader = res.headers['X-Ratelimit-Remaining'] !== undefined || res.headers['X-RateLimit-Remaining'] !== undefined;

  if (res.status === 200) {
    allowedRequests.add(1);
    successRate.add(1);

    check(res, {
      'status is 200 OK': (r) => r.status === 200,
      'RFC 6585 limit header present': () => hasLimitHeader,
      'RFC 6585 remaining header present': () => hasRemainingHeader,
    });
  } else if (res.status === 429) {
    rejectedRequests.add(1);
    successRate.add(0);

    const hasRetryAfter = res.headers['Retry-After'] !== undefined || res.headers['retry-after'] !== undefined;

    check(res, {
      'status is 429 Too Many Requests': (r) => r.status === 429,
      'RFC 6585 Retry-After header present': () => hasRetryAfter,
      'Problem details content-type': (r) => r.headers['Content-Type'] && r.headers['Content-Type'].includes('application/problem+json'),
    });
  }

  // Virtual thread friendly micro-pause
  sleep(0.01);
}
