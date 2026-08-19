package com.lifeos.labs.algorithms.heaps;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Stable bounded top-K selection using a heap of the currently least-preferred retained item.
 *
 * <p>A Finance insights endpoint can use this shape to retain the largest already-authorized
 * category totals without sorting every result. For N inputs and result cap K, it runs in
 * O(N log K) time and O(K) auxiliary space. The supplied comparator defines "better first";
 * first-seen order deterministically resolves ties.
 */
public final class BoundedTopKSelector<T> {

    private final int maxCandidates;

    /** Creates a selector with a strict positive candidate cap. */
    public BoundedTopKSelector(int maxCandidates) {
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("top-K candidate limit must be positive");
        }
        this.maxCandidates = maxCandidates;
    }

    /**
     * Selects an immutable best-first prefix according to {@code betterFirst}.
     *
     * @param candidates caller-authorized candidates
     * @param resultLimit retained result cap in the range {@code [1, maxCandidates]}
     * @param betterFirst negative when its first item should precede the second
     */
    public List<T> select(
            Collection<? extends T> candidates, int resultLimit, Comparator<? super T> betterFirst) {
        if (candidates == null || betterFirst == null) {
            throw new AlgorithmInputException("top-K candidates and comparator are required");
        }
        if (resultLimit < 1 || resultLimit > maxCandidates) {
            throw new AlgorithmInputException("top-K result limit is outside the configured bound");
        }

        Comparator<Indexed<T>> stableBestFirst = Comparator
                .<Indexed<T>, T>comparing(Indexed::value, betterFirst)
                .thenComparingInt(Indexed::firstSeenIndex);
        PriorityQueue<Indexed<T>> retained = new PriorityQueue<>(stableBestFirst.reversed());
        int index = 0;
        for (T candidate : candidates) {
            if (index >= maxCandidates) {
                throw new AlgorithmInputException("top-K candidates exceed the configured limit");
            }
            if (candidate == null) {
                throw new AlgorithmInputException("top-K candidates must not contain null");
            }
            Indexed<T> indexed = new Indexed<>(candidate, index++);
            if (retained.size() < resultLimit) {
                retained.add(indexed);
            } else if (stableBestFirst.compare(indexed, Objects.requireNonNull(retained.peek())) < 0) {
                retained.remove();
                retained.add(indexed);
            }
        }

        List<Indexed<T>> result = new ArrayList<>(retained);
        result.sort(stableBestFirst);
        return result.stream().map(Indexed::value).toList();
    }

    private record Indexed<T>(T value, int firstSeenIndex) {}
}
