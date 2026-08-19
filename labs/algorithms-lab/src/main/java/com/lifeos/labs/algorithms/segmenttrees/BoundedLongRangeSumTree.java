package com.lifeos.labs.algorithms.segmenttrees;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.Objects;

/**
 * Iterative bounded segment tree for mutable integer-minor-unit range sums.
 *
 * <p>A Finance insights projection can use this shape for in-memory day-bucket range queries after
 * an authorized bounded data load. Point replacement and half-open range sum both run in O(log N),
 * with O(N) rounded-power-of-two storage. Production money writes remain immutable ledger rows;
 * this is a read-projection technique, not an accounting source of truth.
 */
public final class BoundedLongRangeSumTree {

    private final int valueCount;
    private final int leafOffset;
    private final long[] tree;

    /** Builds a segment tree from a bounded immutable snapshot of values. */
    public BoundedLongRangeSumTree(long[] values, int maxValues) {
        Objects.requireNonNull(values, "range-sum values must not be null");
        if (maxValues < 1 || values.length < 1 || values.length > maxValues) {
            throw new AlgorithmInputException("range-sum values are outside the configured bound");
        }
        valueCount = values.length;
        leafOffset = nextPowerOfTwo(valueCount);
        tree = new long[Math.multiplyExact(leafOffset, 2)];
        System.arraycopy(values, 0, tree, leafOffset, valueCount);
        for (int index = leafOffset - 1; index > 0; index--) {
            tree[index] = Math.addExact(tree[index * 2], tree[index * 2 + 1]);
        }
    }

    /** Replaces one value and updates its ancestor sums in O(log N) time. */
    public void set(int index, long value) {
        validateIndex(index);
        int treeIndex = leafOffset + index;
        tree[treeIndex] = value;
        for (treeIndex /= 2; treeIndex > 0; treeIndex /= 2) {
            tree[treeIndex] = Math.addExact(tree[treeIndex * 2], tree[treeIndex * 2 + 1]);
        }
    }

    /** Returns the exact sum for the half-open range {@code [fromInclusive, toExclusive)}. */
    public long sum(int fromInclusive, int toExclusive) {
        if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > valueCount) {
            throw new AlgorithmInputException("range-sum bounds are invalid");
        }
        long left = 0;
        long right = 0;
        int start = leafOffset + fromInclusive;
        int end = leafOffset + toExclusive;
        while (start < end) {
            if ((start & 1) == 1) {
                left = Math.addExact(left, tree[start++]);
            }
            if ((end & 1) == 1) {
                right = Math.addExact(tree[--end], right);
            }
            start /= 2;
            end /= 2;
        }
        return Math.addExact(left, right);
    }

    /** Returns the count of original values, excluding padded leaves. */
    public int size() {
        return valueCount;
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= valueCount) {
            throw new AlgorithmInputException("range-sum index is outside the value range");
        }
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            if (result > (Integer.MAX_VALUE / 2)) {
                throw new AlgorithmInputException("range-sum tree is too large");
            }
            result *= 2;
        }
        return result;
    }
}
