package com.lifeos.taskgoal.goal.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void allowsIndependentBranchesInAnyRelativeOrder() {
        List<String> goals = List.of("A", "B", "C", "D");
        List<DependencyEdge> edges = List.of(
                new DependencyEdge("A", "C"),
                new DependencyEdge("B", "D"));

        List<String> order = service.order(goals, edges);

        assertThat(order).hasSize(4);
        assertThat(order.indexOf("A")).isLessThan(order.indexOf("C"));
        assertThat(order.indexOf("B")).isLessThan(order.indexOf("D"));
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
}
