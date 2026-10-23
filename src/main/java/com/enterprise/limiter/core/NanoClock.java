package com.enterprise.limiter.core;

/**
 * High-resolution clock interface providing nanosecond timestamps.
 * Pluggable for deterministic, stepped time-travel in unit tests.
 */
@FunctionalInterface
public interface NanoClock {

    /**
     * Returns the current time in nanoseconds.
     *
     * @return current nanosecond timestamp
     */
    long nanoTime();

    /**
     * Returns a standard clock utilizing System.nanoTime().
     *
     * @return system nano clock
     */
    static NanoClock system() {
        return System::nanoTime;
    }
}
