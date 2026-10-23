# ==============================================================================
# Turnkey Benchmark Runner (PowerShell) — API Rate Limiter
# ==============================================================================

param(
    [string]$Mode = "all",       # "jmh", "k6", or "all"
    [string]$TargetUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$RootDir = Split-Path -Parent $ScriptDir

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "  API RATE LIMITER — BENCHMARK & PERFORMANCE SUITE" -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan

# 1. JMH Microbenchmarks
if ($Mode -eq "jmh" -or $Mode -eq "all") {
    Write-Host "`n[1/2] Running JMH In-Memory TokenBucket Microbenchmark..." -ForegroundColor Yellow
    Push-Location $RootDir
    try {
        & .\mvnw.cmd test-compile
        & .\mvnw.cmd exec:java -Dexec.mainClass="com.enterprise.limiter.benchmark.TokenBucketBenchmark" -Dexec.classpathScope="test"
    } finally {
        Pop-Location
    }
}

# 2. k6 Load Testing
if ($Mode -eq "k6" -or $Mode -eq "all") {
    Write-Host "`n[2/2] Running k6 External Load Test against $TargetUrl..." -ForegroundColor Yellow
    if (Get-Command k6 -ErrorAction SilentlyContinue) {
        $env:TARGET_URL = $TargetUrl
        k6 run "$ScriptDir\k6-rate-limit.js"
    } else {
        Write-Host "k6 is not installed on PATH. Run via Docker:" -ForegroundColor DarkYellow
        Write-Host "docker run --rm -i -e TARGET_URL=$TargetUrl grafana/k6 run - < $ScriptDir\k6-rate-limit.js" -ForegroundColor Green
    }
}

Write-Host "`nBenchmarks Complete!" -ForegroundColor Green
