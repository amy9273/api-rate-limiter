package com.enterprise.limiter.benchmark;

import com.enterprise.limiter.core.NanoClock;
import com.enterprise.limiter.core.TokenBucket;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark measuring raw in-memory throughput of the lock-free
 * {@link TokenBucket#tryConsume(long)} method under concurrent multi-threaded contention.
 *
 * <p>Target Success Criteria: &gt; 5,000,000 ops/sec in-memory with zero GC allocations.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(8)
public class TokenBucketBenchmark {

    private TokenBucket bucket;

    @Setup(Level.Trial)
    public void setup() {
        // High-capacity bucket with infinite refill rate to benchmark pure CAS loop throughput
        this.bucket = new TokenBucket(10_000_000L, 10_000_000L, NanoClock.system());
    }

    @Benchmark
    public boolean benchmarkTryConsume() {
        return bucket.tryConsume(1L);
    }

    /**
     * Self-contained runner allowing direct execution via:
     * {@code java -cp target/test-classes:... com.enterprise.limiter.benchmark.TokenBucketBenchmark}
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(TokenBucketBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(2)
                .measurementIterations(3)
                .threads(8)
                .build();

        new Runner(opt).run();
    }
}
