package com.enterprise.limiter.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TokenBucket Core Specification Tests")
class TokenBucketTest {

    private TestNanoClock clock;

    @BeforeEach
    void setUp() {
        clock = new TestNanoClock(1_000_000_000L);
    }

    @Nested
    @DisplayName("Initialization & Parameter Validation")
    class InitializationTests {

        @Test
        @DisplayName("Should initialize with full capacity by default")
        void shouldInitializeWithFullCapacity() {
            TokenBucket bucket = new TokenBucket(50, 10, clock);

            assertThat(bucket.getCapacity()).isEqualTo(50);
            assertThat(bucket.getRefillTokensPerSecond()).isEqualTo(10);
            assertThat(bucket.getAvailableTokens()).isEqualTo(50);
            assertThat(bucket.calculateNanosUntilNextToken()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should initialize with custom initial tokens")
        void shouldInitializeWithCustomInitialTokens() {
            TokenBucket bucket = new TokenBucket(50, 10, 25, clock);

            assertThat(bucket.getAvailableTokens()).isEqualTo(25);
        }

        @Test
        @DisplayName("Should reject invalid capacity")
        void shouldRejectInvalidCapacity() {
            assertThatThrownBy(() -> new TokenBucket(0, 10, clock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Capacity must be positive");

            assertThatThrownBy(() -> new TokenBucket(-5, 10, clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative refill rate")
        void shouldRejectNegativeRefillRate() {
            assertThatThrownBy(() -> new TokenBucket(10, -1, clock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Refill rate cannot be negative");
        }

        @Test
        @DisplayName("Should reject initial tokens out of bounds")
        void shouldRejectInitialTokensOutOfBounds() {
            assertThatThrownBy(() -> new TokenBucket(10, 1, -1, clock))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new TokenBucket(10, 1, 15, clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject negative or zero tokens on consume")
        void shouldRejectInvalidTokensOnConsume() {
            TokenBucket bucket = new TokenBucket(10, 1, clock);

            assertThatThrownBy(() -> bucket.tryConsume(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> bucket.tryConsume(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Burst Consumption & Boundary Verification")
    class BurstConsumptionTests {

        @Test
        @DisplayName("Should allow burst consumption up to full capacity")
        void shouldAllowBurstConsumptionUpToCapacity() {
            TokenBucket bucket = new TokenBucket(10, 1, clock);

            for (int i = 0; i < 10; i++) {
                assertThat(bucket.tryConsume())
                        .as("Token %d should be consumed successfully", i + 1)
                        .isTrue();
            }

            assertThat(bucket.getAvailableTokens()).isEqualTo(0);
            assertThat(bucket.tryConsume())
                    .as("Consumption beyond capacity without time refill should be rejected")
                    .isFalse();
        }

        @Test
        @DisplayName("Should allow bulk consumption in a single call")
        void shouldAllowBulkConsumption() {
            TokenBucket bucket = new TokenBucket(100, 10, clock);

            assertThat(bucket.tryConsume(60)).isTrue();
            assertThat(bucket.getAvailableTokens()).isEqualTo(40);

            assertThat(bucket.tryConsume(40)).isTrue();
            assertThat(bucket.getAvailableTokens()).isEqualTo(0);

            assertThat(bucket.tryConsume(1)).isFalse();
        }

        @Test
        @DisplayName("Should reject request if tokens requested exceed capacity")
        void shouldRejectRequestExceedingCapacity() {
            TokenBucket bucket = new TokenBucket(10, 5, clock);

            assertThat(bucket.tryConsume(11)).isFalse();
            // Tokens should remain untouched
            assertThat(bucket.getAvailableTokens()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Deterministic Time-Stepped Refill Math")
    class RefillMathTests {

        @Test
        @DisplayName("Should refill tokens accurately over virtual elapsed seconds")
        void shouldRefillTokensOverElapsedSeconds() {
            // Capacity 10, Refill 2 tokens/sec, initialized empty
            TokenBucket bucket = new TokenBucket(10, 2, 0, clock);

            assertThat(bucket.getAvailableTokens()).isEqualTo(0);

            // Advance 1 second -> +2 tokens
            clock.advanceSeconds(1);
            assertThat(bucket.getAvailableTokens()).isEqualTo(2);

            // Advance another 2 seconds -> +4 tokens (total 6)
            clock.advanceSeconds(2);
            assertThat(bucket.getAvailableTokens()).isEqualTo(6);

            // Consume 5 tokens -> 1 token remaining
            assertThat(bucket.tryConsume(5)).isTrue();
            assertThat(bucket.getAvailableTokens()).isEqualTo(1);

            // Advance 5 seconds -> +10 tokens, but capped at capacity (10)
            clock.advanceSeconds(5);
            assertThat(bucket.getAvailableTokens()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should carry forward sub-second fractional nanoseconds without token loss")
        void shouldCarryForwardFractionalNanoseconds() {
            // 2 tokens per second means 1 token every 500,000,000 nanoseconds
            TokenBucket bucket = new TokenBucket(10, 2, 0, clock);

            // Advance 300ms: not enough for 1 token yet (needs 500ms)
            clock.advanceMillis(300);
            assertThat(bucket.getAvailableTokens()).isEqualTo(0);

            // Advance another 300ms (total 600ms): 1 token refilled, 100ms carried over
            clock.advanceMillis(300);
            assertThat(bucket.getAvailableTokens()).isEqualTo(1);

            // Advance another 400ms (total 1000ms): exactly 2 tokens produced total
            clock.advanceMillis(400);
            assertThat(bucket.getAvailableTokens()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should never exceed maximum capacity even after long idle periods")
        void shouldCapAtMaximumCapacityAfterLongIdle() {
            TokenBucket bucket = new TokenBucket(50, 10, 0, clock);

            // Advance 100,000 seconds (long idle)
            clock.advanceSeconds(100_000);

            assertThat(bucket.getAvailableTokens()).isEqualTo(50);
            assertThat(bucket.tryConsume(50)).isTrue();
            assertThat(bucket.getAvailableTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should accurately compute nanoseconds until next token and retry-after")
        void shouldAccuratelyComputeNanosUntilNextToken() {
            // 10 tokens per second = 1 token per 100,000,000 nanoseconds (100ms)
            TokenBucket bucket = new TokenBucket(10, 10, 0, clock);

            // Empty bucket at t=0
            long nanos = bucket.calculateNanosUntilNextToken();
            assertThat(nanos).isEqualTo(100_000_000L);
            assertThat(bucket.calculateRetryAfterSeconds()).isEqualTo(1L);

            // Advance 40ms -> 60ms remaining
            clock.advanceMillis(40);
            assertThat(bucket.calculateNanosUntilNextToken()).isEqualTo(60_000_000L);

            // Advance 60ms -> 1 token available, wait time should now be 0
            clock.advanceMillis(60);
            assertThat(bucket.calculateNanosUntilNextToken()).isEqualTo(0L);
            assertThat(bucket.calculateRetryAfterSeconds()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Multi-Threaded Concurrency & Zero Race Conditions")
    class ConcurrencyTests {

        @Test
        @DisplayName("100 concurrent threads competing for 1,000 tokens: exactly 1,000 succeed and 1,000 fail")
        void shouldHandle100ConcurrentThreadsWithZeroRaceConditions() throws InterruptedException {
            final int capacity = 1_000;
            // Zero refill rate to isolate burst concurrency and race conditions
            TokenBucket bucket = new TokenBucket(capacity, 0, clock);

            final int threadCount = 100;
            final int requestsPerThread = 20; // 100 * 20 = 2,000 total attempts for 1,000 tokens

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger rejectCount = new AtomicInteger(0);
            AtomicReference<Throwable> threadError = new AtomicReference<>(null);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await(); // Hold all threads until all 100 are ready
                        for (int r = 0; r < requestsPerThread; r++) {
                            if (bucket.tryConsume()) {
                                successCount.incrementAndGet();
                            } else {
                                rejectCount.incrementAndGet();
                            }
                        }
                    } catch (Throwable t) {
                        threadError.compareAndSet(null, t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Wait for all 100 threads to be ready, then trigger synchronized stampede
            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            // Wait for completion
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(threadError.get()).isNull();
            assertThat(successCount.get())
                    .as("Exactly 1,000 requests must succeed under 100 competing threads")
                    .isEqualTo(1_000);
            assertThat(rejectCount.get())
                    .as("Exactly 1,000 requests must be rejected when capacity is exhausted")
                    .isEqualTo(1_000);
            assertThat(bucket.getAvailableTokens())
                    .as("Bucket balance must be exactly 0 with zero negative leakage")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("100 Virtual Threads should consume tokens concurrently with zero mutex lockups")
        void shouldHandleVirtualThreadsWithoutLockContention() throws InterruptedException {
            final int capacity = 500;
            TokenBucket bucket = new TokenBucket(capacity, 0, clock);

            final int threadCount = 100;
            final int requestsPerThread = 10; // 1,000 total attempts for 500 tokens

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger rejectCount = new AtomicInteger(0);

            try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                CountDownLatch doneLatch = new CountDownLatch(threadCount);

                for (int i = 0; i < threadCount; i++) {
                    virtualExecutor.submit(() -> {
                        try {
                            for (int r = 0; r < requestsPerThread; r++) {
                                if (bucket.tryConsume()) {
                                    successCount.incrementAndGet();
                                } else {
                                    rejectCount.incrementAndGet();
                                }
                            }
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(successCount.get()).isEqualTo(500);
            assertThat(rejectCount.get()).isEqualTo(500);
            assertThat(bucket.getAvailableTokens()).isEqualTo(0);
        }
    }
}
