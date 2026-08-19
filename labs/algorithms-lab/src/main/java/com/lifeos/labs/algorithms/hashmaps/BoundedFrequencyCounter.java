package com.lifeos.labs.algorithms.hashmaps;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable bounded frequency counting for categories in a Finance insight projection.
 *
 * <p>For N submitted values and D distinct values it runs in O(N) expected time and O(D) space.
 * The insertion-ordered map makes output deterministic while explicit caps prevent a caller from
 * turning a histogram request into unbounded memory use.
 */
public final class BoundedFrequencyCounter {

    private final int maxValues;
    private final int maxDistinctValues;

    /** Creates a counter with explicit submitted-value and distinct-value caps. */
    public BoundedFrequencyCounter(int maxValues, int maxDistinctValues) {
        if (maxValues < 1 || maxDistinctValues < 1 || maxDistinctValues > maxValues) {
            throw new IllegalArgumentException("frequency-counter bounds are invalid");
        }
        this.maxValues = maxValues;
        this.maxDistinctValues = maxDistinctValues;
    }

    /** Returns an immutable first-seen ordered frequency map. */
    public <T> Map<T, Integer> count(Collection<? extends T> values) {
        if (values == null) {
            throw new AlgorithmInputException("frequency values are required");
        }
        Map<T, Integer> counts = new LinkedHashMap<>();
        int submitted = 0;
        for (T value : values) {
            if (++submitted > maxValues) {
                throw new AlgorithmInputException("frequency values exceed the configured limit");
            }
            if (value == null) {
                throw new AlgorithmInputException("frequency values must not contain null");
            }
            if (!counts.containsKey(value) && counts.size() >= maxDistinctValues) {
                throw new AlgorithmInputException("frequency distinct values exceed the configured limit");
            }
            counts.merge(value, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
