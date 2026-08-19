package com.lifeos.labs.algorithms.unionfind;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded Union-Find with path compression and union by rank.
 *
 * <p>A Profile/Household relationship validation projection can use this shape to detect whether
 * two already-authorized members are connected without repeatedly traversing every relationship.
 * Add, union, and connectivity checks are amortized O(alpha(N)); retained state is O(N). This lab
 * does not model household permissions or replace durable relationship storage.
 */
public final class BoundedDisjointSet<T> {

    private final int maxElements;
    private final Map<T, Integer> indexes = new LinkedHashMap<>();
    private final int[] parents;
    private final byte[] ranks;
    private int components;

    /** Creates an empty disjoint set with a strict positive element cap. */
    public BoundedDisjointSet(int maxElements) {
        if (maxElements < 1) {
            throw new IllegalArgumentException("disjoint-set limit must be positive");
        }
        this.maxElements = maxElements;
        parents = new int[maxElements];
        ranks = new byte[maxElements];
    }

    /** Adds an element as its own component if it has not already been added. */
    public boolean add(T value) {
        Objects.requireNonNull(value, "disjoint-set values must not be null");
        if (indexes.containsKey(value)) {
            return false;
        }
        if (indexes.size() >= maxElements) {
            throw new AlgorithmInputException("disjoint-set exceeds the configured element limit");
        }
        int index = indexes.size();
        indexes.put(value, index);
        parents[index] = index;
        components++;
        return true;
    }

    /** Joins two existing components and returns whether their connectivity changed. */
    public boolean union(T first, T second) {
        int firstRoot = findIndex(indexOf(first));
        int secondRoot = findIndex(indexOf(second));
        if (firstRoot == secondRoot) {
            return false;
        }
        if (ranks[firstRoot] < ranks[secondRoot]) {
            parents[firstRoot] = secondRoot;
        } else if (ranks[firstRoot] > ranks[secondRoot]) {
            parents[secondRoot] = firstRoot;
        } else {
            parents[secondRoot] = firstRoot;
            ranks[firstRoot]++;
        }
        components--;
        return true;
    }

    /** Returns whether two existing elements belong to the same connected component. */
    public boolean connected(T first, T second) {
        return findIndex(indexOf(first)) == findIndex(indexOf(second));
    }

    /** Returns the number of current components. */
    public int componentCount() {
        return components;
    }

    private int indexOf(T value) {
        Integer index = indexes.get(Objects.requireNonNull(value, "disjoint-set values must not be null"));
        if (index == null) {
            throw new AlgorithmInputException("disjoint-set value is not registered");
        }
        return index;
    }

    private int findIndex(int index) {
        int root = index;
        while (parents[root] != root) {
            root = parents[root];
        }
        while (parents[index] != index) {
            int parent = parents[index];
            parents[index] = root;
            index = parent;
        }
        return root;
    }
}
