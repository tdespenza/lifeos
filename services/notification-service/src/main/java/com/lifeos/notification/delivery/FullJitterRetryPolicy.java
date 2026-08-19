package com.lifeos.notification.delivery;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Capped exponential full-jitter retry policy. Its output is bounded, including under malformed
 * high attempt counts, so failure storms do not create unbounded delays or integer overflow.
 */
public final class FullJitterRetryPolicy {

    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final RandomGenerator random;

    public FullJitterRetryPolicy(Duration initialBackoff, Duration maximumBackoff, RandomGenerator random) {
        this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        this.maximumBackoff = requirePositive(maximumBackoff, "maximumBackoff");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be lower than initialBackoff");
        }
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Returns a full-jitter delay in {@code [0, min(max, initial * 2^(attempt-1))]}.
     *
     * @param attempt one-based completed attempt number
     */
    public Duration nextDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least one");
        }
        long capNanos = cappedNanos(attempt);
        if (capNanos == 0) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(random.nextLong(capNanos + 1));
    }

    private long cappedNanos(int attempt) {
        long maximumNanos = maximumBackoff.toNanos();
        long candidate = initialBackoff.toNanos();
        for (int index = 1; index < attempt && candidate < maximumNanos; index++) {
            candidate = candidate > maximumNanos / 2 ? maximumNanos : candidate * 2;
        }
        return Math.min(candidate, maximumNanos);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
