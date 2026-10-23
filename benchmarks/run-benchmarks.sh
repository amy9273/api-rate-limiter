#!/usr/bin/env bash
# ==============================================================================
# Turnkey Benchmark Runner (Bash) — API Rate Limiter
# ==============================================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET_URL="${TARGET_URL:-http://localhost:8080}"
MODE="${1:-all}"

echo "=================================================================="
echo "  API RATE LIMITER — BENCHMARK & PERFORMANCE SUITE"
echo "=================================================================="

# 1. JMH Microbenchmarks
if [[ "$MODE" == "jmh" || "$MODE" == "all" ]]; then
    echo -e "\n[1/2] Running JMH In-Memory TokenBucket Microbenchmark..."
    cd "$ROOT_DIR"
    ./mvnw test-compile
    ./mvnw exec:java -Dexec.mainClass="com.enterprise.limiter.benchmark.TokenBucketBenchmark" -Dexec.classpathScope="test"
fi

# 2. k6 Load Testing
if [[ "$MODE" == "k6" || "$MODE" == "all" ]]; then
    echo -e "\n[2/2] Running k6 External Load Test against $TARGET_URL..."
    if command -v k6 &> /dev/null; then
        TARGET_URL="$TARGET_URL" k6 run "$SCRIPT_DIR/k6-rate-limit.js"
    elif command -v docker &> /dev/null; then
        echo "k6 CLI not found locally; executing via official Docker container..."
        docker run --rm -i -e TARGET_URL="$TARGET_URL" grafana/k6 run - < "$SCRIPT_DIR/k6-rate-limit.js"
    else
        echo "Neither k6 nor docker installed. Please install k6: https://k6.io/docs/get-started/installation/"
    fi
fi

echo -e "\nBenchmarks Complete!"
