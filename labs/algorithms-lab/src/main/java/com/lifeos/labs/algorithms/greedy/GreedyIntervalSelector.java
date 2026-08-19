package com.lifeos.labs.algorithms.greedy;

import com.lifeos.algorithms.AlgorithmInputException;
import com.lifeos.algorithms.interval.TimeInterval;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Earliest-finish-time interval scheduling with deterministic input-order ties.
 *
 * <p>Calendar can use this pattern to propose a maximum-count non-overlapping focus-block set
 * after authorization and recurrence expansion. The greedy exchange argument shows that choosing
 * the earliest finishing compatible interval leaves the most room for every later choice. It runs
 * in O(N log N) time and O(N) space for N bounded intervals.
 */
public final class GreedyIntervalSelector {

    private final int maxIntervals;

    /** Creates a selector with a strict positive interval cap. */
    public GreedyIntervalSelector(int maxIntervals) {
        if (maxIntervals < 1) {
            throw new IllegalArgumentException("interval-selector limit must be positive");
        }
        this.maxIntervals = maxIntervals;
    }

    /** Returns an immutable maximum-cardinality set of compatible half-open intervals. */
    public <T> List<TimeInterval<T>> select(Collection<? extends TimeInterval<T>> intervals) {
        if (intervals == null) {
            throw new AlgorithmInputException("interval-selector intervals are required");
        }
        List<IndexedInterval<T>> ordered = new ArrayList<>();
        int index = 0;
        for (TimeInterval<T> interval : intervals) {
            if (interval == null) {
                throw new AlgorithmInputException("interval-selector intervals must not contain null");
            }
            if (index >= maxIntervals) {
                throw new AlgorithmInputException("interval-selector intervals exceed the configured limit");
            }
            ordered.add(new IndexedInterval<>(interval, index++));
        }
        ordered.sort(IndexedInterval.byEndThenStartThenIndex());

        List<TimeInterval<T>> selected = new ArrayList<>();
        Instant previousEnd = null;
        for (IndexedInterval<T> candidate : ordered) {
            if (previousEnd == null || !candidate.interval().startsAt().isBefore(previousEnd)) {
                selected.add(candidate.interval());
                previousEnd = candidate.interval().endsAt();
            }
        }
        return List.copyOf(selected);
    }

    private record IndexedInterval<T>(TimeInterval<T> interval, int firstSeenIndex) {

        private static <T> Comparator<IndexedInterval<T>> byEndThenStartThenIndex() {
            return Comparator.comparing((IndexedInterval<T> value) -> value.interval().endsAt())
                    .thenComparing(value -> value.interval().startsAt())
                    .thenComparingInt(IndexedInterval::firstSeenIndex);
        }
    }
}
