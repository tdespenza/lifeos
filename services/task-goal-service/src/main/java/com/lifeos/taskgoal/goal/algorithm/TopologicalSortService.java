package com.lifeos.taskgoal.goal.algorithm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Kahn's algorithm: O(V + E) time, O(V + E) space. Used to order goals so that
 * every goal appears after all the goals it depends on.
 */
@Service
public class TopologicalSortService {

    public List<String> order(List<String> nodes, List<DependencyEdge> edges) {
        Set<String> allNodes = new LinkedHashSet<>(nodes);
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (String node : allNodes) {
            adjacency.put(node, new ArrayList<>());
            inDegree.put(node, 0);
        }

        for (DependencyEdge edge : edges) {
            allNodes.add(edge.before());
            allNodes.add(edge.after());
            adjacency.computeIfAbsent(edge.before(), key -> new ArrayList<>()).add(edge.after());
            inDegree.merge(edge.after(), 1, Integer::sum);
            inDegree.putIfAbsent(edge.before(), 0);
        }

        Deque<String> ready = new ArrayDeque<>();
        for (String node : allNodes) {
            if (inDegree.getOrDefault(node, 0) == 0) {
                ready.add(node);
            }
        }

        List<String> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            result.add(current);
            for (String next : adjacency.getOrDefault(current, List.of())) {
                int remaining = inDegree.merge(next, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(next);
                }
            }
        }

        if (result.size() != allNodes.size()) {
            List<String> unresolved = allNodes.stream()
                    .filter(node -> !result.contains(node))
                    .toList();
            throw new CyclicDependencyException(unresolved);
        }

        return result;
    }
}
