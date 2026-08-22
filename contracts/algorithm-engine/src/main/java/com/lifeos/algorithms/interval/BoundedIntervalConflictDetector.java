package com.lifeos.algorithms.interval;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeSet;

/**
 * Enumerates all half-open interval conflicts with bounded input and output.
 *
 * <p>Intervals are sorted once, expired intervals are evicted through a min-heap, and currently
 * active intervals are kept in a deterministic tree. For N input intervals and C retained
 * conflicts, runtime is O(N log N + C) and space is O(N + C). If the method discovers conflict
 * {@code maxConflicts + 1}, it throws instead of returning a truncated result. A direct nested
 * pair scan is simpler, but it requires O(N^2) time even when few intervals overlap. The result
 * order is stable by the later interval's normalized start/end/first-seen rank and then the
 * earlier interval's same rank.
 */
public final class BoundedIntervalConflictDetector {

    public static final int DEFAULT_MAX_INTERVALS = 10_000;
    public static final int DEFAULT_MAX_CONFLICTS = 50_000;

    private final int maxIntervals;
    private final int maxConflicts;

    /** Creates the standard bounded calendar conflict detector. */
    public BoundedIntervalConflictDetector() {
        this(DEFAULT_MAX_INTERVALS, DEFAULT_MAX_CONFLICTS);
    }

    /**
     * Creates a detector with service-owned input/output bounds.
     *
     * @param maxIntervals maximum intervals inspected
     * @param maxConflicts maximum result pairs retained
     */
    public BoundedIntervalConflictDetector(int maxIntervals, int maxConflicts) {
        if (maxIntervals < 1 || maxConflicts < 0) {
            throw new IllegalArgumentException(
                    "maxIntervals must be at least 1 and maxConflicts must be non-negative");
        }
        this.maxIntervals = maxIntervals;
        this.maxConflicts = maxConflicts;
    }

    /**
     * Finds every overlap without treating adjacent endpoints as conflicts.
     *
     * @param intervals normalized, caller-authorized intervals
     * @param <T> immutable interval value type
     * @return immutable deterministic conflict pairs
     */
    public <T> List<IntervalConflict<T>> findConflicts(Collection<? extends TimeInterval<T>> intervals) {
        if (intervals == null) {
            throw new AlgorithmInputException("interval collection is required");
        }
        List<IndexedInterval<T>> ordered = new ArrayList<>();
        int index = 0;
        for (TimeInterval<T> interval : intervals) {
            if (interval == null) {
                throw new AlgorithmInputException("interval collection must not contain null");
            }
            if (index >= maxIntervals) {
                throw new AlgorithmInputException("interval collection exceeds the configured limit");
            }
            ordered.add(new IndexedInterval<>(interval, index++));
        }
        ordered.sort(IndexedInterval.byStartThenEndThenIndex());

        TreeSet<IndexedInterval<T>> activeByOrder = new TreeSet<>(IndexedInterval.byStartThenEndThenIndex());
        PriorityQueue<IndexedInterval<T>> activeByEnd =
                new PriorityQueue<>(IndexedInterval.byEndThenIndex());
        List<IntervalConflict<T>> conflicts = new ArrayList<>();

        for (IndexedInterval<T> current : ordered) {
            while (!activeByEnd.isEmpty()
                    && !activeByEnd.peek().interval().endsAt().isAfter(current.interval().startsAt())) {
                activeByOrder.remove(activeByEnd.remove());
            }
            for (IndexedInterval<T> earlier : activeByOrder) {
                if (conflicts.size() >= maxConflicts) {
                    throw new AlgorithmInputException("interval conflicts exceed the configured result limit");
                }
                conflicts.add(new IntervalConflict<>(earlier.interval(), current.interval()));
            }
            activeByOrder.add(current);
            activeByEnd.add(current);
        }
        return List.copyOf(conflicts);
    }

    private record IndexedInterval<T>(TimeInterval<T> interval, int firstSeenIndex) {

        private static <T> Comparator<IndexedInterval<T>> byStartThenEndThenIndex() {
            return Comparator.comparing((IndexedInterval<T> value) -> value.interval().startsAt())
                    .thenComparing(value -> value.interval().endsAt())
                    .thenComparingInt(IndexedInterval::firstSeenIndex);
        }

        private static <T> Comparator<IndexedInterval<T>> byEndThenIndex() {
            return Comparator.comparing((IndexedInterval<T> value) -> value.interval().endsAt())
                    .thenComparingInt(IndexedInterval::firstSeenIndex);
        }
    }
}
