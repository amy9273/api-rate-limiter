# Spec 05 — Production Docker, CI/CD Hardening & Benchmarking Suite

---

## 1. Goal & Objectives

Establish production-grade containerization, CI/CD pipeline validation, and benchmarking harnesses to prove the rate limiter meets all performance criteria:
1. **Multi-Stage Production Dockerfile:** Hardened, non-root Alpine container with Spring Boot layered JAR optimization and container-aware JVM flags.
2. **JMH Microbenchmark Harness:** Benchmark isolated in-memory `TokenBucket.tryConsume()` throughput to verify $> 5,000,000$ operations/sec with zero lock contention.
3. **Turnkey Load Testing Suite (`k6` / `wrk`):** Provide scripts to execute load tests simulating concurrent clients and verifying p50, p90, and p99 latency overhead $< 1.0\text{ ms}$.
4. **CI/CD Pipeline Hardening:** Update `.github/workflows/ci.yml` to compile, test, package, and verify the Docker container build.
5. **Documentation & README:** Update `README.md` with benchmark execution instructions, Docker commands, and verified performance results.

---

## 2. Requirements & Deliverables

### Deliverable 1: Multi-Stage Production `Dockerfile`
- Base: `eclipse-temurin:21-jdk-alpine` (builder) & `eclipse-temurin:21-jre-alpine` (runner).
- Security: Runs as unprivileged `appuser:appgroup`.
- Layered extraction: Separates dependencies, spring-boot-loader, and application code for optimal Docker layer caching.
- JVM Flags: `-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`.
- Health Check: Validates `/actuator/health` using `wget`.

### Deliverable 2: JMH In-Memory Microbenchmarks
- Benchmark class: `TokenBucketBenchmark` measuring `tryConsume()` ops/sec under multi-threaded concurrency.
- Assert throughput targets ($> 5,000,000$ ops/sec in-memory) and zero lock contention.
- Self-executable `main` runner method allowing on-demand profiling.

### Deliverable 3: Turnkey External Load Testing (`k6` & `wrk`)
- `benchmarks/k6-rate-limit.js`: Load test ramping from 1,000 to 10,000+ virtual users, evaluating RFC 6585 response headers, 429 status transitions, and p99 latency thresholds.
- `benchmarks/wrk-pipeline.lua`: High-throughput Lua benchmark script for `wrk` testing client API key rotation.
- Turnkey runner scripts (`run-benchmarks.sh` & `run-benchmarks.ps1`).

### Deliverable 4: Enhanced GitHub Actions CI/CD Pipeline
- `.github/workflows/ci.yml` upgraded to test build, package production JAR, and verify Docker image build.

---

## 3. Verification Criteria
1. Multi-stage `Dockerfile` builds a minimal, non-root, runnable image.
2. JMH benchmark compiles and runs cleanly, validating sub-microsecond atomic CAS throughput.
3. `k6` and `wrk` scripts are present, documented, and syntactically valid.
4. GitHub Actions workflow compiles, executes tests, and verifies Docker image creation.
5. `mvn clean test` runs with 100% pass rate.
