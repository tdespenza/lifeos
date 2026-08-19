package com.lifeos.labs.algorithms.arrays;

import java.util.OptionalDouble;

/**
 * Fixed-memory rolling average for a bounded sequence of integer minor-unit observations.
 *
 * <p>This maps to a Finance dashboard's recent daily-spend smoothing. Each append runs in O(1)
 * time and the ring buffer uses O(W) space for a configured window W. It intentionally does not
 * calculate currency conversion or choose a reporting window; the calling domain owns both.
 */
public final class BoundedRollingAverage {

    private final long[] values;
    private int nextIndex;
    private int size;
    private long total;

    /** Creates a rolling window that retains at most {@code capacity} observations. */
    public BoundedRollingAverage(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("rolling-average capacity must be positive");
        }
        values = new long[capacity];
    }

    /**
     * Adds one observation, replacing the oldest observation once the window is full.
     *
     * @throws ArithmeticException if a caller chooses values whose sum cannot fit in a long
     */
    public void add(long value) {
        if (size == values.length) {
            total = Math.subtractExact(total, values[nextIndex]);
        } else {
            size++;
        }
        values[nextIndex] = value;
        total = Math.addExact(total, value);
        nextIndex = (nextIndex + 1) % values.length;
    }

    /** Returns no value before the first observation, otherwise the current exact double average. */
    public OptionalDouble average() {
        return size == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) total / size);
    }

    /** Returns the number of retained observations. */
    public int size() {
        return size;
    }

    /** Returns the immutable configured window size. */
    public int capacity() {
        return values.length;
    }
}
