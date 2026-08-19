package com.lifeos.documentvault.proof;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Capped exponential full-jitter retry delay; attempts are always finite. */
public final class DocumentProofOutboxRetryPolicy {

    private final Duration initial;
    private final Duration maximum;

    public DocumentProofOutboxRetryPolicy(Duration initial, Duration maximum) {
        if (initial == null || maximum == null || initial.isNegative() || initial.isZero()
                || maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("retry durations must be positive and ordered");
        }
        this.initial = initial;
        this.maximum = maximum;
    }

    public Duration nextDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        long capNanos;
        try {
            capNanos = Math.multiplyExact(initial.toNanos(), 1L << Math.min(attempt - 1, 30));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            capNanos = Long.MAX_VALUE;
        }
        capNanos = Math.min(capNanos, maximum.toNanos());
        return Duration.ofNanos(ThreadLocalRandom.current().nextLong(1, Math.max(2, capNanos + 1)));
    }
}
