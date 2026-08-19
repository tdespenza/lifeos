package com.lifeos.labs.algorithms.fenwick;

import com.lifeos.algorithms.AlgorithmInputException;

/**
 * Fixed-size Fenwick tree for incremental daily analytics totals.
 *
 * <p>Analytics can use this structure to apply a bounded event delta and answer prefix/range sums
 * without rebuilding every bucket. Add and prefix sum are O(log N), range sum is O(log N), and
 * storage is O(N). It is an educational in-memory projection, not a replacement for durable
 * event consumption, idempotency, or accounting history.
 */
public final class BoundedLongFenwickTree {

    private final long[] tree;

    /** Creates an empty tree for exactly {@code size} zero-based logical buckets. */
    public BoundedLongFenwickTree(int size, int maxSize) {
        if (size < 1 || maxSize < 1 || size > maxSize) {
            throw new AlgorithmInputException("Fenwick tree size is outside the configured bound");
        }
        tree = new long[size + 1];
    }

    /** Adds a delta to one zero-based bucket in O(log N) time. */
    public void add(int index, long delta) {
        validateIndex(index);
        for (int treeIndex = index + 1; treeIndex < tree.length; treeIndex += treeIndex & -treeIndex) {
            tree[treeIndex] = Math.addExact(tree[treeIndex], delta);
        }
    }

    /** Returns the sum of buckets in {@code [0, endExclusive)}. */
    public long prefixSum(int endExclusive) {
        if (endExclusive < 0 || endExclusive > size()) {
            throw new AlgorithmInputException("Fenwick prefix bound is invalid");
        }
        long sum = 0;
        for (int treeIndex = endExclusive; treeIndex > 0; treeIndex -= treeIndex & -treeIndex) {
            sum = Math.addExact(sum, tree[treeIndex]);
        }
        return sum;
    }

    /** Returns the exact sum for the half-open range {@code [fromInclusive, toExclusive)}. */
    public long rangeSum(int fromInclusive, int toExclusive) {
        if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > size()) {
            throw new AlgorithmInputException("Fenwick range bound is invalid");
        }
        return Math.subtractExact(prefixSum(toExclusive), prefixSum(fromInclusive));
    }

    /** Returns the number of logical zero-based buckets. */
    public int size() {
        return tree.length - 1;
    }

    private void validateIndex(int index) {
        if (index < 0 || index >= size()) {
            throw new AlgorithmInputException("Fenwick index is outside the value range");
        }
    }
}
