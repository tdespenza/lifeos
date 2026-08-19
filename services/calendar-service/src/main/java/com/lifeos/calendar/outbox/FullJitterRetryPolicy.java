package com.lifeos.calendar.outbox;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Bounded exponential retry with full jitter, avoiding synchronized retry storms. */
public class FullJitterRetryPolicy {

    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final RandomGenerator randomGenerator;

    public FullJitterRetryPolicy(Duration initialBackoff, Duration maxBackoff, RandomGenerator randomGenerator) {
        if (initialBackoff == null
                || maxBackoff == null
                || initialBackoff.isNegative()
                || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("retry backoff bounds are invalid");
        }
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.randomGenerator = Objects.requireNonNull(randomGenerator, "randomGenerator must not be null");
    }

    /** Computes a random duration in [0, min(maxBackoff, initialBackoff * 2^(attempt-1))]. */
    public Duration nextDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        long maximumMillis = maxBackoff.toMillis();
        long cap = initialBackoff.toMillis();
        for (int value = 1; value < attempt && cap < maximumMillis; value++) {
            cap = cap > maximumMillis / 2 ? maximumMillis : cap * 2;
        }
        return Duration.ofMillis(randomGenerator.nextLong(cap + 1));
    }
}
