package com.lifeos.labs.algorithms.trees;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A bounded unbalanced binary-search tree with deterministic in-order traversal.
 *
 * <p>This demonstrates the in-memory ordered-index concept behind a Calendar range projection.
 * Production Calendar uses database indexes and pagination instead; this lab intentionally shows
 * why an unbalanced tree has O(H) insert/lookup and can degrade to O(N) for ordered input. Its
 * fixed node cap keeps that worst case finite.
 */
public final class BoundedBinarySearchTree<T extends Comparable<? super T>> {

    private final int maxNodes;
    private Node<T> root;
    private int size;

    /** Creates a tree with a strict positive node cap. */
    public BoundedBinarySearchTree(int maxNodes) {
        if (maxNodes < 1) {
            throw new IllegalArgumentException("tree node limit must be positive");
        }
        this.maxNodes = maxNodes;
    }

    /**
     * Inserts a value if absent.
     *
     * @return true for a new value, false for a duplicate
     */
    public boolean add(T value) {
        Objects.requireNonNull(value, "tree values must not be null");
        if (root == null) {
            ensureCapacity();
            root = new Node<>(value);
            size = 1;
            return true;
        }

        Node<T> current = root;
        while (true) {
            int comparison = value.compareTo(current.value);
            if (comparison == 0) {
                return false;
            }
            if (comparison < 0) {
                if (current.left == null) {
                    ensureCapacity();
                    current.left = new Node<>(value);
                    size++;
                    return true;
                }
                current = current.left;
            } else {
                if (current.right == null) {
                    ensureCapacity();
                    current.right = new Node<>(value);
                    size++;
                    return true;
                }
                current = current.right;
            }
        }
    }

    /** Returns whether the tree contains a value in O(H) time for current height H. */
    public boolean contains(T value) {
        Objects.requireNonNull(value, "tree values must not be null");
        Node<T> current = root;
        while (current != null) {
            int comparison = value.compareTo(current.value);
            if (comparison == 0) {
                return true;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return false;
    }

    /** Returns values in strictly ascending order without recursive stack growth. */
    public List<T> inOrder() {
        List<T> ordered = new ArrayList<>(size);
        List<Node<T>> stack = new ArrayList<>();
        Node<T> current = root;
        while (current != null || !stack.isEmpty()) {
            while (current != null) {
                stack.add(current);
                current = current.left;
            }
            current = stack.removeLast();
            ordered.add(current.value);
            current = current.right;
        }
        return List.copyOf(ordered);
    }

    /** Returns the number of unique values currently stored. */
    public int size() {
        return size;
    }

    private void ensureCapacity() {
        if (size >= maxNodes) {
            throw new AlgorithmInputException("tree exceeds the configured node limit");
        }
    }

    private static final class Node<T> {

        private final T value;
        private Node<T> left;
        private Node<T> right;

        private Node(T value) {
            this.value = value;
        }
    }
}
