package com.enterprise.limiter.core;

import com.enterprise.limiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BucketRegistry Specification & TTL Eviction Tests")
class BucketRegistryTest {

    private TestNanoClock clock;
    private RateLimiterProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private BucketRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new TestNanoClock(1_000_000_000L);
        properties = new RateLimiterProperties();
        properties.setDefaultCapacity(50);
        properties.setDefaultRefillRate(10);
        properties.setBucketTtl(Duration.ofMinutes(10));
        properties.setCleanupInterval(Duration.ofSeconds(60));

        meterRegistry = new SimpleMeterRegistry();
        registry = new BucketRegistry(properties, clock, meterRegistry);
    }

    @Nested
    @DisplayName("Registry Initialization & Bucket Retrieval")
    class RetrievalTests {

        @Test
        @DisplayName("Should create bucket with default properties if absent")
        void shouldCreateBucketWithDefaultProperties() {
            TokenBucket bucket = registry.getOrCreate("ip:192.168.1.1");

            assertThat(bucket).isNotNull();
            assertThat(bucket.getCapacity()).isEqualTo(50);
            assertThat(bucket.getRefillTokensPerSecond()).isEqualTo(10);
            assertThat(registry.getActiveBucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return same TokenBucket instance for same client key")
        void shouldReturnSameInstanceForSameKey() {
            TokenBucket bucket1 = registry.getOrCreate("api_key:alpha");
            TokenBucket bucket2 = registry.getOrCreate("api_key:alpha");

            assertThat(bucket1).isSameAs(bucket2);
            assertThat(registry.getActiveBucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should support custom capacity and refill rate per client key")
        void shouldSupportCustomCapacityAndRefill() {
            TokenBucket premium = registry.getOrCreate("tenant:vip-enterprise", 1000, 200);

            assertThat(premium.getCapacity()).isEqualTo(1000);
            assertThat(premium.getRefillTokensPerSecond()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should atomically consume tokens through registry")
        void shouldConsumeThroughRegistry() {
            String key = "ip:10.0.0.1";

            boolean success = registry.tryConsume(key, 5);
            assertThat(success).isTrue();

            TokenBucket bucket = registry.getOrCreate(key);
            assertThat(bucket.getAvailableTokens()).isEqualTo(45);
        }
    }

    @Nested
    @DisplayName("Sliding TTL & Expired Bucket Eviction")
    class EvictionTests {

        @Test
        @DisplayName("Should evict inactive buckets while preserving recently accessed buckets")
        void shouldEvictInactiveBucketsAndPreserveActive() {
            // t = 0 min: create buckets A, B, C
            registry.getOrCreate("client_A");
            registry.getOrCreate("client_B");
            registry.getOrCreate("client_C");
            assertThat(registry.getActiveBucketCount()).isEqualTo(3);

            // Advance clock to t = 6 min (within 10m TTL)
            clock.advanceMinutes(6);

            // Refresh client_A and client_B activity
            registry.tryConsume("client_A");
            registry.tryConsume("client_B");

            // Advance clock to t = 11 min (client_C is idle for 11m > 10m TTL, client_A and client_B idle for 5m < 10m TTL)
            clock.advanceMinutes(5);

            int evicted = registry.evictExpiredBuckets();

            assertThat(evicted).as("Only client_C should be evicted").isEqualTo(1);
            assertThat(registry.getActiveBucketCount()).isEqualTo(2);
            assertThat(registry.getEntry("client_C")).isNull();
            assertThat(registry.getEntry("client_A")).isNotNull();
            assertThat(registry.getEntry("client_B")).isNotNull();

            // Advance clock to t = 17 min (another 6m elapsed, now client_A and client_B have been idle for 11m)
            clock.advanceMinutes(6);

            int secondEvicted = registry.evictExpiredBuckets();
            assertThat(secondEvicted).isEqualTo(2);
            assertThat(registry.getActiveBucketCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should update Micrometer metrics on bucket addition and eviction")
        void shouldUpdateMicrometerMetrics() {
            registry.getOrCreate("client_1");
            registry.getOrCreate("client_2");

            Double activeGauge = meterRegistry.get("rate_limiter_active_buckets").gauge().value();
            assertThat(activeGauge).isEqualTo(2.0);

            // Advance clock past TTL (11 minutes)
            clock.advanceMinutes(11);
            registry.evictExpiredBuckets();

            Double activeAfterEvict = meterRegistry.get("rate_limiter_active_buckets").gauge().value();
            assertThat(activeAfterEvict).isEqualTo(0.0);

            double totalEvicted = meterRegistry.get("rate_limiter_evictions_total").counter().count();
            assertThat(totalEvicted).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("Multi-Threaded Registry Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("100 concurrent threads creating and consuming tokens across 50 keys: exactly 2,500 succeed")
        void shouldHandle100ConcurrentThreadsSafely() throws Exception {
            final int threadCount = 100;
            final int uniqueKeys = 50;
            final int operationsPerThread = 50;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger totalSuccessfulConsumptions = new AtomicInteger(0);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    try {
                        for (int op = 0; op < operationsPerThread; op++) {
                            String key = "client_" + ((threadId + op) % uniqueKeys);
                            if (registry.tryConsume(key)) {
                                totalSuccessfulConsumptions.incrementAndGet();
                            }
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                    return null;
                }));
            }

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS); // Propagate any asynchronous exceptions
            }
            executor.shutdown();

            assertThat(registry.getActiveBucketCount()).isEqualTo(uniqueKeys);
            // 50 keys with 50 tokens each = exactly 2,500 tokens consumed with zero time advance
            assertThat(totalSuccessfulConsumptions.get())
                    .as("Exactly 2,500 requests must succeed across 50 keys with capacity 50")
                    .isEqualTo(2_500);

            for (int k = 0; k < uniqueKeys; k++) {
                assertThat(registry.getOrCreate("client_" + k).getAvailableTokens()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("Should enforce max active buckets capacity and shed excess ephemeral clients")
        void shouldEnforceMaxActiveBucketsCapacity() {
            RateLimiterProperties boundedProps = new RateLimiterProperties(50, 10, Duration.ofMinutes(10), Duration.ofSeconds(60), true, 3);
            SimpleMeterRegistry mr = new SimpleMeterRegistry();
            BucketRegistry boundedRegistry = new BucketRegistry(boundedProps, clock, mr);

            boundedRegistry.getOrCreate("client_1");
            boundedRegistry.getOrCreate("client_2");
            boundedRegistry.getOrCreate("client_3");
            assertThat(boundedRegistry.getActiveBucketCount()).isEqualTo(3);

            // 4th client exceeds capacity of 3 with none expired yet
            assertThatThrownBy(() -> boundedRegistry.getOrCreate("client_4"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capacity exceeded");

            double overflowCount = mr.get("rate_limiter_registry_overflow_total").counter().count();
            assertThat(overflowCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Concurrent eviction sweeps racing with client acquisitions execute with zero race exceptions")
        void shouldSafelyHandleConcurrentEvictionAndAccess() throws Exception {
            final int clientCount = 50;
            for (int i = 0; i < clientCount; i++) {
                registry.getOrCreate("stale_" + i);
            }
            assertThat(registry.getActiveBucketCount()).isEqualTo(clientCount);

            // Advance clock past TTL (11 minutes) so they become eligible for eviction
            clock.advanceMinutes(11);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            // 4 threads performing eviction sweeps
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    registry.evictExpiredBuckets();
                    return null;
                }));
            }

            // 4 threads concurrently accessing and creating fresh buckets
            for (int i = 0; i < 4; i++) {
                final int threadId = i;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    for (int j = 0; j < 25; j++) {
                        registry.tryConsume("active_" + (threadId * 25 + j));
                    }
                    return null;
                }));
            }

            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();

            // All 100 new active entries should be successfully recorded without deadlocks
            assertThat(registry.getActiveBucketCount()).isGreaterThanOrEqualTo(100);
        }
    }
}
