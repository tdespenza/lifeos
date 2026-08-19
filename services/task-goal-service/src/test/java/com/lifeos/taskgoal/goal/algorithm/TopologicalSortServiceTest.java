package com.lifeos.taskgoal.goal.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologicalSortServiceTest {

    private final TopologicalSortService service = new TopologicalSortService();

    @Test
    void ordersNodesSoDependenciesComeFirst() {
        List<String> goals = List.of("A", "B", "C");
        List<DependencyEdge> edges = List.of(
                new DependencyEdge("A", "B"),
                new DependencyEdge("B", "C"));

        List<String> order = service.order(goals, edges);

        assertThat(order).containsExactly("A", "B", "C");
    }

    @Test
    void ordersIndependentBranchesUsingOriginalNodeOrderAsStableTieBreaker() {
        List<String> goals = List.of("A", "B", "C", "D");
        List<DependencyEdge> edges = List.of(
                new DependencyEdge("A", "C"),
                new DependencyEdge("B", "D"));

        List<String> order = service.order(goals, edges);

        assertThat(order).containsExactly("A", "B", "C", "D");
    }

    @Test
    void includesNodesWithNoDependencies() {
        List<String> goals = List.of("A", "B", "Standalone");
        List<DependencyEdge> edges = List.of(new DependencyEdge("A", "B"));

        List<String> order = service.order(goals, edges);

        assertThat(order).containsExactlyInAnyOrder("A", "B", "Standalone");
    }

    @Test
    void detectsDirectCycle() {
        List<String> goals = List.of("A", "B");
        List<DependencyEdge> edges = List.of(
                new DependencyEdge("A", "B"),
                new DependencyEdge("B", "A"));

        assertThatThrownBy(() -> service.order(goals, edges))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test
    void detectsIndirectCycle() {
        List<String> goals = List.of("A", "B", "C");
        List<DependencyEdge> edges = List.of(
                new DependencyEdge("A", "B"),
                new DependencyEdge("B", "C"),
                new DependencyEdge("C", "A"));

        assertThatThrownBy(() -> service.order(goals, edges))
                .isInstanceOf(CyclicDependencyException.class)
                .satisfies(ex -> assertThat(((CyclicDependencyException) ex).getUnresolved())
                        .containsExactlyInAnyOrder("A", "B", "C"));
    }

    @Test
    void isolatesCycleFromUnaffectedNodes() {
        List<String> goals = List.of("A", "B", "Standalone");
        List<DependencyEdge> edges = List.of(
                new DependencyEdge("A", "B"),
                new DependencyEdge("B", "A"));

        assertThatThrownBy(() -> service.order(goals, edges))
                .isInstanceOf(CyclicDependencyException.class)
                .satisfies(ex -> assertThat(((CyclicDependencyException) ex).getUnresolved())
                        .containsExactlyInAnyOrder("A", "B")
                        .doesNotContain("Standalone"));
    }

    @Test
    void returnsAnUnmodifiableOrderAndDeduplicatesRepeatedEdges() {
        List<String> order = service.order(
                List.of("A", "B"),
                List.of(new DependencyEdge("A", "B"), new DependencyEdge("A", "B")));

        assertThat(order).containsExactly("A", "B");
        assertThatThrownBy(() -> order.add("C")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedDuplicateAndUnboundedInputsBeforeSorting() {
        assertThatThrownBy(() -> service.order(null, List.of()))
                .isInstanceOf(InvalidDependencyGraphException.class);
        assertThatThrownBy(() -> service.order(List.of("A"), null))
                .isInstanceOf(InvalidDependencyGraphException.class);
        assertThatThrownBy(() -> service.order(List.of("A", "A"), List.of()))
                .isInstanceOf(InvalidDependencyGraphException.class);
        assertThatThrownBy(() -> service.order(List.of("\n"), List.of()))
                .isInstanceOf(InvalidDependencyGraphException.class);
        assertThatThrownBy(() -> service.order(
                        Collections.nCopies(TopologicalSortService.MAX_NODES + 1, "A"), List.of()))
                .isInstanceOf(InvalidDependencyGraphException.class);
        assertThatThrownBy(() -> service.order(
                        List.of("A"),
                        Collections.nCopies(
                                TopologicalSortService.MAX_EDGES + 1, new DependencyEdge("A", "A"))))
                .isInstanceOf(InvalidDependencyGraphException.class);
    }

    @Test
    void rejectsTooManyDistinctNodesIntroducedOnlyByEdges() {
        List<DependencyEdge> edges = java.util.stream.IntStream.range(0, TopologicalSortService.MAX_NODES)
                .mapToObj(index -> new DependencyEdge("node-" + index, "node-" + (index + 1)))
                .toList();

        assertThatThrownBy(() -> service.order(List.of("root"), edges))
                .isInstanceOf(InvalidDependencyGraphException.class);
    }
}
