package com.lifeos.labs.algorithms.backtracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedSlotAssignmentPlannerTest {

    @Test
    void backtracksToFindTheFirstDeterministicFeasiblePlan() {
        BoundedSlotAssignmentPlanner planner = new BoundedSlotAssignmentPlanner(4, 4, 3, 20);

        List<BoundedSlotAssignmentPlanner.SlotAssignment<String>> assignments = planner.assign(
                        List.of(
                                new BoundedSlotAssignmentPlanner.SlotCandidate<>("research", List.of(0, 1)),
                                new BoundedSlotAssignmentPlanner.SlotCandidate<>("review", List.of(0))),
                        2)
                .orElseThrow();

        assertEquals(
                List.of(
                        new BoundedSlotAssignmentPlanner.SlotAssignment<>("research", 1),
                        new BoundedSlotAssignmentPlanner.SlotAssignment<>("review", 0)),
                assignments);
    }

    @Test
    void returnsEmptyForAnUnsatisfiablePlanAndRejectsAnUnboundedSearch() {
        BoundedSlotAssignmentPlanner planner = new BoundedSlotAssignmentPlanner(3, 3, 2, 2);

        assertFalse(planner.assign(
                        List.of(
                                new BoundedSlotAssignmentPlanner.SlotCandidate<>("one", List.of(0)),
                                new BoundedSlotAssignmentPlanner.SlotCandidate<>("two", List.of(0))),
                        1)
                .isPresent());
        assertThrows(
                AlgorithmInputException.class,
                () -> planner.assign(
                        List.of(
                                new BoundedSlotAssignmentPlanner.SlotCandidate<>("one", List.of(0, 1)),
                                new BoundedSlotAssignmentPlanner.SlotCandidate<>("two", List.of(0, 1))),
                        2));
    }
}
