package com.enterprise.limiter.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic, controllable nanosecond clock for time-stepped testing.
 */
public class TestNanoClock implements NanoClock {

    private final AtomicLong currentNanos;

    public TestNanoClock() {
        this(1_000_000_000L); // Start at 1 second in virtual nanoseconds
    }

    public TestNanoClock(long initialNanos) {
        this.currentNanos = new AtomicLong(initialNanos);
    }

    @Override
    public long nanoTime() {
        return currentNanos.get();
    }

    public void advanceNanos(long nanos) {
        currentNanos.addAndGet(nanos);
    }

    public void advanceMillis(long millis) {
        advanceNanos(millis * 1_000_000L);
    }

    public void advanceSeconds(long seconds) {
        advanceNanos(seconds * 1_000_000_000L);
    }

    public void setNanos(long nanos) {
        currentNanos.set(nanos);
    }
}
