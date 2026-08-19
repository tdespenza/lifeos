package com.lifeos.taskgoal.goal.algorithm;

import com.lifeos.algorithms.AlgorithmCycleException;
import com.lifeos.algorithms.AlgorithmInputException;
import com.lifeos.algorithms.graph.BoundedTopologicalOrder;
import com.lifeos.algorithms.graph.DirectedEdge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Compatibility adapter for the legacy free-text endpoint. It delegates ordering to the shared
 * bounded Kahn primitive while retaining its historical stricter label validation and unresolved
 * label diagnostic. Persisted Task/Goal ordering uses the same shared primitive directly.
 */
@Service
public class TopologicalSortService {

    /** Maximum distinct nodes accepted by the synchronous dependency-order endpoint. */
    public static final int MAX_NODES = 10_000;

    /** Maximum submitted edges accepted by the synchronous dependency-order endpoint. */
    public static final int MAX_EDGES = 50_000;

    /** Maximum user-provided node-label length retained in memory or returned to the caller. */
    public static final int MAX_NODE_LABEL_LENGTH = 128;

    private final BoundedTopologicalOrder orderer = new BoundedTopologicalOrder(MAX_NODES, MAX_EDGES);

    public List<String> order(List<String> nodes, List<DependencyEdge> edges) {
        validateCollections(nodes, edges);

        Set<String> allNodes = new LinkedHashSet<>();

        for (String node : nodes) {
            addNode(node, allNodes, true);
        }

        List<DirectedEdge<String>> sharedEdges = new ArrayList<>(edges.size());
        for (DependencyEdge edge : edges) {
            validateEdge(edge);
            addNode(edge.before(), allNodes, false);
            addNode(edge.after(), allNodes, false);
            sharedEdges.add(new DirectedEdge<>(edge.before(), edge.after()));
        }

        try {
            return orderer.order(allNodes, sharedEdges);
        } catch (AlgorithmCycleException exception) {
            // The shared algorithm intentionally does not expose node values. This legacy HTTP
            // contract did, so retain a bounded diagnostic-only pass without using it to order.
            throw new CyclicDependencyException(unresolved(allNodes, sharedEdges));
        } catch (AlgorithmInputException exception) {
            throw new InvalidDependencyGraphException();
        }
    }

    private static void validateCollections(List<String> nodes, List<DependencyEdge> edges) {
        if (nodes == null || edges == null || nodes.isEmpty() || nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
            throw new InvalidDependencyGraphException();
        }
    }

    private static void addNode(
            String node,
            Set<String> allNodes,
            boolean rejectDuplicateExplicitNode) {
        validateNode(node);
        if (allNodes.contains(node)) {
            if (rejectDuplicateExplicitNode) {
                throw new InvalidDependencyGraphException();
            }
            return;
        }
        if (allNodes.size() >= MAX_NODES) {
            throw new InvalidDependencyGraphException();
        }
        allNodes.add(node);
    }

    private static void validateEdge(DependencyEdge edge) {
        if (edge == null) {
            throw new InvalidDependencyGraphException();
        }
        validateNode(edge.before());
        validateNode(edge.after());
    }

    private static void validateNode(String node) {
        if (node == null
                || node.isBlank()
                || node.length() > MAX_NODE_LABEL_LENGTH
                || node.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidDependencyGraphException();
        }
    }

    /** Retains the legacy unresolved-label diagnostic after the shared orderer detects a cycle. */
    private static List<String> unresolved(
            Set<String> allNodes, List<DirectedEdge<String>> submittedEdges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        allNodes.forEach(node -> {
            adjacency.put(node, new ArrayList<>());
            indegree.put(node, 0);
        });
        Set<DirectedEdge<String>> uniqueEdges = new LinkedHashSet<>();
        for (DirectedEdge<String> edge : submittedEdges) {
            if (uniqueEdges.add(edge)) {
                adjacency.get(edge.before()).add(edge.after());
                indegree.compute(edge.after(), (ignored, value) -> value + 1);
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.addLast(node);
            }
        });
        Set<String> resolved = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            resolved.add(current);
            for (String dependent : adjacency.get(current)) {
                int remaining = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        return allNodes.stream().filter(node -> !resolved.contains(node)).toList();
    }
}
