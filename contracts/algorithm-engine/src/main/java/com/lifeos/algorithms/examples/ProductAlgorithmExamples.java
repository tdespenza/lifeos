package com.lifeos.algorithms.examples;

import com.lifeos.algorithms.graph.BoundedTopologicalOrder;
import com.lifeos.algorithms.graph.DirectedEdge;
import com.lifeos.algorithms.interval.BoundedIntervalConflictDetector;
import com.lifeos.algorithms.interval.IntervalConflict;
import com.lifeos.algorithms.interval.TimeInterval;
import com.lifeos.algorithms.ranking.BoundedPriorityRanker;
import com.lifeos.algorithms.ranking.PrioritizedItem;
import java.time.Instant;
import java.util.List;

/**
 * Small deterministic, product-backed examples for interview practice.
 *
 * <p>The examples deliberately use opaque labels and fixed instants rather than user data or the
 * current clock. Production services authorize and bound their local projections before invoking
 * the same primitives. See {@code docs/algorithms/product-backed-examples.md} for the problem
 * framing, correctness argument, complexity, and failure boundaries for each example.
 */
public final class ProductAlgorithmExamples {

    private ProductAlgorithmExamples() {}

    /**
     * Orders a Goal before its prerequisite and dependent Tasks.
     *
     * <p>This demonstrates the persisted Task/Goal dependency execution-order use case. Kahn's
     * algorithm emits a node only after every prerequisite has been emitted, so every returned
     * directed edge points forward in the result. A cycle rejects the whole plan rather than
     * returning a misleading partial plan.
     */
    public static List<String> taskGoalExecutionOrder() {
        return new BoundedTopologicalOrder().order(
                List.of("goal:launch", "task:research", "task:build", "task:review"),
                List.of(
                        new DirectedEdge<>("task:research", "goal:launch"),
                        new DirectedEdge<>("goal:launch", "task:build"),
                        new DirectedEdge<>("task:build", "task:review")));
    }

    /**
     * Detects a Calendar event/time-block overlap while treating adjacent slots as available.
     *
     * <p>Half-open intervals mean the review block ending at 10:00 does not conflict with the
     * focus block beginning at 10:00. The planning event overlaps only the review block.
     */
    public static List<IntervalConflict<String>> calendarConflicts() {
        Instant dayStart = Instant.parse("2026-08-18T09:00:00Z");
        return new BoundedIntervalConflictDetector().findConflicts(
                List.of(
                        new TimeInterval<>("event:planning", dayStart, dayStart.plusSeconds(3_600)),
                        new TimeInterval<>(
                                "block:review",
                                dayStart.plusSeconds(2_700),
                                dayStart.plusSeconds(3_600)),
                        new TimeInterval<>(
                                "block:focus",
                                dayStart.plusSeconds(3_600),
                                dayStart.plusSeconds(5_400))));
    }

    /**
     * Produces a deterministic, explainable Calendar focus queue from already-authorized work.
     *
     * <p>The ranker orders higher priority first, then the earlier deadline, then first-seen
     * input. It does not calculate a score or make an AI recommendation; that domain policy stays
     * with the calling service.
     */
    public static List<PrioritizedItem<String>> calendarFocusQueue() {
        Instant dayStart = Instant.parse("2026-08-18T09:00:00Z");
        return new BoundedPriorityRanker().rank(
                List.of(
                        new PrioritizedItem<>("task:review", 80, dayStart.plusSeconds(7_200)),
                        new PrioritizedItem<>("task:prepare", 100, dayStart.plusSeconds(10_800)),
                        new PrioritizedItem<>("task:inbox-zero", 80, null)),
                3);
    }
}
