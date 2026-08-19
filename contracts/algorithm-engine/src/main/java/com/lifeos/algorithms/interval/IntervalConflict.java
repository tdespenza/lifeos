package com.lifeos.algorithms.interval;

import java.util.Objects;

/** One deterministically ordered pair of overlapping normalized intervals. */
public record IntervalConflict<T>(TimeInterval<T> earlier, TimeInterval<T> later) {

    public IntervalConflict {
        Objects.requireNonNull(earlier, "earlier must not be null");
        Objects.requireNonNull(later, "later must not be null");
    }
}
