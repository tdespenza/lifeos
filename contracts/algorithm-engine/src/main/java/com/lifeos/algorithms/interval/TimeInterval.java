package com.lifeos.algorithms.interval;

import java.time.Instant;
import java.util.Objects;

/**
 * A normalized half-open interval {@code [startsAt, endsAt)}. Adjacent intervals are therefore
 * not conflicts.
 */
public record TimeInterval<T>(T value, Instant startsAt, Instant endsAt) {

    public TimeInterval {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("interval start must be before its end");
        }
    }
}
