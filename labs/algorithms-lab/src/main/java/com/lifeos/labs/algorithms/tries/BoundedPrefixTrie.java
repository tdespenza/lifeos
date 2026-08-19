package com.lifeos.labs.algorithms.tries;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bounded deterministic prefix trie for an already-authorized Document Vault term projection.
 *
 * <p>Insertion and exact lookup take O(L) for token length L. Prefix suggestion takes O(P + R)
 * plus traversal work for P prefix characters and R returned terms, with a caller-provided result
 * cap. The trie uses lexicographically ordered child maps for deterministic output, not global
 * mutable indexing state.
 */
public final class BoundedPrefixTrie {

    private final int maxNodes;
    private final int maxWordCodePoints;
    private Node root = new Node();
    private int nodeCount = 1;

    /** Creates a trie with strict node and per-word code-point caps. */
    public BoundedPrefixTrie(int maxNodes, int maxWordCodePoints) {
        if (maxNodes < 1 || maxWordCodePoints < 1) {
            throw new IllegalArgumentException("trie bounds must be positive");
        }
        this.maxNodes = maxNodes;
        this.maxWordCodePoints = maxWordCodePoints;
    }

    /** Inserts a normalized caller-owned term if it is not already present. */
    public boolean add(String word) {
        validateWord(word);
        Node current = root;
        for (int offset = 0; offset < word.length(); ) {
            int codePoint = word.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Node next = current.children.get(codePoint);
            if (next == null) {
                if (nodeCount >= maxNodes) {
                    throw new AlgorithmInputException("trie exceeds the configured node limit");
                }
                next = new Node();
                current.children.put(codePoint, next);
                nodeCount++;
            }
            current = next;
        }
        if (current.terminal) {
            return false;
        }
        current.terminal = true;
        return true;
    }

    /** Returns exact term membership in O(L) time. */
    public boolean contains(String word) {
        validateWord(word);
        Node current = root;
        for (int offset = 0; offset < word.length(); ) {
            int codePoint = word.codePointAt(offset);
            offset += Character.charCount(codePoint);
            current = current.children.get(codePoint);
            if (current == null) {
                return false;
            }
        }
        return current.terminal;
    }

    /** Returns at most {@code limit} lexicographically ordered terms with the supplied prefix. */
    public List<String> suggest(String prefix, int limit) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (prefix.codePointCount(0, prefix.length()) > maxWordCodePoints) {
            throw new AlgorithmInputException("trie prefix exceeds the configured limit");
        }
        if (limit < 1 || limit > maxNodes) {
            throw new AlgorithmInputException("trie suggestion limit is outside the configured bound");
        }

        Node current = root;
        for (int offset = 0; offset < prefix.length(); ) {
            int codePoint = prefix.codePointAt(offset);
            offset += Character.charCount(codePoint);
            current = current.children.get(codePoint);
            if (current == null) {
                return List.of();
            }
        }
        List<String> suggestions = new ArrayList<>(Math.min(limit, 16));
        collect(current, new StringBuilder(prefix), limit, suggestions);
        return List.copyOf(suggestions);
    }

    /** Returns the number of allocated nodes, including the root. */
    public int nodeCount() {
        return nodeCount;
    }

    private void collect(Node node, StringBuilder prefix, int limit, List<String> suggestions) {
        if (suggestions.size() >= limit) {
            return;
        }
        if (node.terminal) {
            suggestions.add(prefix.toString());
        }
        for (var child : node.children.entrySet()) {
            int originalLength = prefix.length();
            prefix.appendCodePoint(child.getKey());
            collect(child.getValue(), prefix, limit, suggestions);
            prefix.setLength(originalLength);
            if (suggestions.size() >= limit) {
                return;
            }
        }
    }

    private void validateWord(String word) {
        if (word == null || word.isEmpty()) {
            throw new AlgorithmInputException("trie words must be non-empty");
        }
        if (word.codePointCount(0, word.length()) > maxWordCodePoints) {
            throw new AlgorithmInputException("trie words exceed the configured limit");
        }
    }

    private static final class Node {

        private final TreeMap<Integer, Node> children = new TreeMap<>();
        private boolean terminal;
    }
}
