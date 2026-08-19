package com.lifeos.labs.algorithms.linkedlists;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fixed-capacity LRU cache built from a hash map and a private doubly linked list.
 *
 * <p>A Document Vault presentation layer could use this for short-lived, already-authorized
 * metadata projections; authorization and invalidation still belong to that caller. Each get and
 * put is O(1) expected time, and the cache uses O(C) memory for its fixed capacity C.
 */
public final class BoundedLruCache<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> nodes = new HashMap<>();
    private Node<K, V> leastRecent;
    private Node<K, V> mostRecent;

    /** Creates an empty cache with a strict positive entry cap. */
    public BoundedLruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("LRU capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Returns and promotes a cached value without invoking a loader or external dependency. */
    public Optional<V> get(K key) {
        Node<K, V> node = nodes.get(Objects.requireNonNull(key, "key must not be null"));
        if (node == null) {
            return Optional.empty();
        }
        moveToMostRecent(node);
        return Optional.of(node.value);
    }

    /** Adds or replaces a non-null entry, evicting exactly one least-recent entry when full. */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Node<K, V> existing = nodes.get(key);
        if (existing != null) {
            existing.value = value;
            moveToMostRecent(existing);
            return;
        }

        Node<K, V> inserted = new Node<>(key, value);
        nodes.put(key, inserted);
        append(inserted);
        if (nodes.size() > capacity) {
            evictLeastRecent();
        }
    }

    /** Returns the current bounded entry count. */
    public int size() {
        return nodes.size();
    }

    private void moveToMostRecent(Node<K, V> node) {
        if (node == mostRecent) {
            return;
        }
        unlink(node);
        append(node);
    }

    private void evictLeastRecent() {
        Node<K, V> evicted = leastRecent;
        unlink(Objects.requireNonNull(evicted));
        nodes.remove(evicted.key);
    }

    private void append(Node<K, V> node) {
        node.previous = mostRecent;
        node.next = null;
        if (mostRecent == null) {
            leastRecent = node;
        } else {
            mostRecent.next = node;
        }
        mostRecent = node;
    }

    private void unlink(Node<K, V> node) {
        if (node.previous == null) {
            leastRecent = node.next;
        } else {
            node.previous.next = node.next;
        }
        if (node.next == null) {
            mostRecent = node.previous;
        } else {
            node.next.previous = node.previous;
        }
        node.previous = null;
        node.next = null;
    }

    private static final class Node<K, V> {

        private final K key;
        private V value;
        private Node<K, V> previous;
        private Node<K, V> next;

        private Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
