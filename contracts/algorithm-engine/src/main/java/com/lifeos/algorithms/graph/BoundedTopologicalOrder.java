package com.lifeos.algorithms.graph;

import com.lifeos.algorithms.AlgorithmCycleException;
import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Iterative Kahn topological ordering with explicit graph-size limits and stable first-seen
 * tie-breaking.
 *
 * <p>For {@code N} submitted node records, {@code R} submitted edge records, {@code V} distinct
 * nodes, and {@code E} unique edges, the algorithm runs in O(N + R + V + E) time and uses
 * O(V + E) memory. The {@code maxSubmittedNodes} and {@code maxSubmittedEdges} bounds cap
 * duplicate-record work. Repeated identical edges are normalized before indegrees are updated, so
 * duplicate input does not change the result. Cyclic input throws before returning any partial
 * order.
 */
public final class BoundedTopologicalOrder {

    public static final int DEFAULT_MAX_NODES = 10_000;
    public static final int DEFAULT_MAX_SUBMITTED_NODES = 50_000;
    public static final int DEFAULT_MAX_SUBMITTED_EDGES = 50_000;

    private final int maxNodes;
    private final int maxSubmittedNodes;
    private final int maxSubmittedEdges;

    /** Creates the standard planning-graph bounded orderer. */
    public BoundedTopologicalOrder() {
        this(DEFAULT_MAX_NODES, DEFAULT_MAX_SUBMITTED_NODES, DEFAULT_MAX_SUBMITTED_EDGES);
    }

    /**
     * Creates an orderer with service-owned bounds.
     *
     * @param maxNodes maximum distinct explicit or edge-referenced nodes
     * @param maxSubmittedNodes maximum declared-node records inspected, including duplicates
     * @param maxSubmittedEdges maximum edge records inspected, including duplicates
     */
    public BoundedTopologicalOrder(int maxNodes, int maxSubmittedNodes, int maxSubmittedEdges) {
        if (maxNodes < 1 || maxSubmittedNodes < 0 || maxSubmittedEdges < 0) {
            throw new IllegalArgumentException(
                    "maxNodes must be at least 1, and maxSubmittedNodes/maxSubmittedEdges must be non-negative");
        }
        this.maxNodes = maxNodes;
        this.maxSubmittedNodes = maxSubmittedNodes;
        this.maxSubmittedEdges = maxSubmittedEdges;
    }

    /**
     * Produces a complete deterministic order. The first appearance of an otherwise-ready node is
     * its stable tie-breaker, whether it appears in {@code declaredNodes} or an edge endpoint.
     *
     * @param declaredNodes caller-authorized nodes; duplicate values are normalized
     * @param edges caller-authorized directed edges; duplicate edges are normalized
     * @param <T> immutable, equality-stable node identifier type
     * @return immutable complete order
     * @throws AlgorithmInputException for null or over-bound input
     * @throws AlgorithmCycleException when no complete topological order exists
     */
    public <T> List<T> order(
            Collection<? extends T> declaredNodes, Collection<? extends DirectedEdge<T>> edges) {
        if (declaredNodes == null || edges == null) {
            throw new AlgorithmInputException("graph nodes and edges are required");
        }

        Map<T, Set<T>> adjacency = new LinkedHashMap<>();
        Map<T, Integer> indegree = new LinkedHashMap<>();
        int submittedNodeCount = 0;
        for (T node : declaredNodes) {
            if (++submittedNodeCount > maxSubmittedNodes) {
                throw new AlgorithmInputException("graph exceeds the configured node record limit");
            }
            addNode(adjacency, indegree, node);
        }

        int submittedEdgeCount = 0;
        for (DirectedEdge<T> edge : edges) {
            if (++submittedEdgeCount > maxSubmittedEdges) {
                throw new AlgorithmInputException("graph exceeds the configured edge limit");
            }
            if (edge == null) {
                throw new AlgorithmInputException("graph edges must not contain null");
            }
            addNode(adjacency, indegree, edge.before());
            addNode(adjacency, indegree, edge.after());
            if (adjacency.get(edge.before()).add(edge.after())) {
                indegree.compute(edge.after(), (ignored, value) -> value + 1);
            }
        }

        ArrayDeque<T> ready = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.addLast(node);
            }
        });

        List<T> ordered = new ArrayList<>(indegree.size());
        while (!ready.isEmpty()) {
            T node = ready.removeFirst();
            ordered.add(node);
            for (T dependent : adjacency.get(node)) {
                int remaining = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        if (ordered.size() != indegree.size()) {
            throw new AlgorithmCycleException();
        }
        return List.copyOf(ordered);
    }

    private <T> void addNode(Map<T, Set<T>> adjacency, Map<T, Integer> indegree, T node) {
        if (node == null) {
            throw new AlgorithmInputException("graph nodes must not contain null");
        }
        if (adjacency.containsKey(node)) {
            return;
        }
        if (adjacency.size() >= maxNodes) {
            throw new AlgorithmInputException("graph exceeds the configured node limit");
        }
        adjacency.put(Objects.requireNonNull(node), new LinkedHashSet<>());
        indegree.put(node, 0);
    }
}
