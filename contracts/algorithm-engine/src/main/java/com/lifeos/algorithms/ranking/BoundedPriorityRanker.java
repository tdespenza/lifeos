package com.lifeos.algorithms.ranking;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Stable bounded ranking for planning candidates.
 *
 * <p>Higher {@code priorityScore} wins; then an earlier non-null due time; then first-seen input
 * order. For N candidates, where N does not exceed {@code maxCandidates}, it runs in O(N log N)
 * time and O(N) additional space. If the candidate collection exceeds the configured bound, or
 * {@code limit} is invalid, it throws instead of returning a truncated result. A bounded heap can
 * select a small prefix in O(N log L) time, but sorting the full bounded input keeps ordering
 * simple and deterministic. A null due time is intentionally ranked after any actual deadline.
 */
public final class BoundedPriorityRanker {

    public static final int DEFAULT_MAX_CANDIDATES = 10_000;

    private final int maxCandidates;

    /** Creates the standard bounded ranker. */
    public BoundedPriorityRanker() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    /** Creates a ranker with a service-owned candidate cap. */
    public BoundedPriorityRanker(int maxCandidates) {
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("candidate limit must be positive");
        }
        this.maxCandidates = maxCandidates;
    }

    /**
     * Returns the first {@code limit} candidates in deterministic ranking order.
     *
     * @param candidates candidate collection
     * @param limit maximum number of results to retain
     * @param <T> immutable candidate value type
     * @return immutable ranked prefix
     */
    public <T> List<PrioritizedItem<T>> rank(Collection<? extends PrioritizedItem<T>> candidates, int limit) {
        if (candidates == null) {
            throw new AlgorithmInputException("ranking candidates are required");
        }
        if (limit < 1 || limit > maxCandidates) {
            throw new AlgorithmInputException("ranking limit is outside the configured bound");
        }

        List<IndexedItem<T>> indexed = new ArrayList<>();
        int index = 0;
        for (PrioritizedItem<T> candidate : candidates) {
            if (candidate == null) {
                throw new AlgorithmInputException("ranking candidates must not contain null");
            }
            if (index >= maxCandidates) {
                throw new AlgorithmInputException("ranking candidates exceed the configured limit");
            }
            indexed.add(new IndexedItem<>(candidate, index++));
        }
        indexed.sort(IndexedItem.byPriorityThenDeadlineThenIndex());

        int resultSize = Math.min(limit, indexed.size());
        List<PrioritizedItem<T>> result = new ArrayList<>(resultSize);
        for (int candidateIndex = 0; candidateIndex < resultSize; candidateIndex++) {
            result.add(indexed.get(candidateIndex).item());
        }
        return List.copyOf(result);
    }

    private record IndexedItem<T>(PrioritizedItem<T> item, int firstSeenIndex) {

        private static <T> Comparator<IndexedItem<T>> byPriorityThenDeadlineThenIndex() {
            return Comparator.<IndexedItem<T>>comparingInt(value -> value.item().priorityScore())
                    .reversed()
                    .thenComparing(
                            value -> value.item().dueAt(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(IndexedItem::firstSeenIndex);
        }
    }
}
