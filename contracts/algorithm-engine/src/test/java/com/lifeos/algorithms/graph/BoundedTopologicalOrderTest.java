package com.lifeos.algorithms.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmCycleException;
import com.lifeos.algorithms.AlgorithmInputException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedTopologicalOrderTest {

    private final BoundedTopologicalOrder orderer = new BoundedTopologicalOrder(10, 10, 10);

    @Test
    void ordersAnAcyclicGraphWithFirstSeenStableTies() {
        List<String> result = orderer.order(
                List.of("write", "review", "ship", "document"),
                List.of(new DirectedEdge<>("write", "review"), new DirectedEdge<>("review", "ship")));

        assertEquals(List.of("write", "document", "review", "ship"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add("unexpected"));
    }

    @Test
    void addsEdgeOnlyNodesInFirstSeenOrderAndNormalizesDuplicateEdges() {
        List<String> result = orderer.order(
                List.of("first"),
                List.of(
                        new DirectedEdge<>("second", "third"),
                        new DirectedEdge<>("second", "third"),
                        new DirectedEdge<>("first", "third")));

        assertEquals(List.of("first", "second", "third"), result);
    }

    @Test
    void rejectsCyclesWithoutReturningAPartialOrder() {
        assertThrows(
                AlgorithmCycleException.class,
                () -> orderer.order(
                        List.of("a", "b", "c"),
                        List.of(new DirectedEdge<>("a", "b"), new DirectedEdge<>("b", "a"))));
    }

    @Test
    void rejectsNullAndOversizedInputBeforeSorting() {
        assertThrows(AlgorithmInputException.class, () -> orderer.order(null, List.of()));
        assertThrows(AlgorithmInputException.class, () -> orderer.order(List.of(), null));
        assertThrows(
                AlgorithmInputException.class,
                () -> orderer.order(Arrays.asList("a", null), List.of()));
        assertThrows(
                AlgorithmInputException.class,
                () -> orderer.order(
                        List.of("a", "b", "c"), Arrays.asList(new DirectedEdge<>("a", "b"), null)));
        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedTopologicalOrder(2, 2, 2).order(List.of("a", "b", "c"), List.of()));
        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedTopologicalOrder(4, 10, 1)
                        .order(
                                List.of("a", "b"),
                                List.of(new DirectedEdge<>("a", "b"), new DirectedEdge<>("b", "c"))));
    }

    @Test
    void rejectsInvalidConfiguredBounds() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopologicalOrder(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopologicalOrder(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedTopologicalOrder(1, 1, -1));
    }

    @Test
    void rejectsDeclaredNodeRecordsExceedingTheSubmittedNodeLimitEvenWhenAllDuplicates() {
        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedTopologicalOrder(10, 3, 10)
                        .order(List.of("same", "same", "same", "same"), List.of()));
    }

    @Test
    void rejectsDuplicateEdgeRecordsExceedingTheSubmittedEdgeLimit() {
        DirectedEdge<String> edge = new DirectedEdge<>("a", "b");

        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedTopologicalOrder(10, 10, 1).order(List.of("a", "b"), List.of(edge, edge)));
    }
}
