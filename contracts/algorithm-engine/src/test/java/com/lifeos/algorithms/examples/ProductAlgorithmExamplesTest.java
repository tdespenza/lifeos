package com.lifeos.algorithms.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lifeos.algorithms.interval.IntervalConflict;
import com.lifeos.algorithms.ranking.PrioritizedItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductAlgorithmExamplesTest {

    @Test
    void ordersTheProductBackedTaskAndGoalExample() {
        assertEquals(
                List.of("task:research", "goal:launch", "task:build", "task:review"),
                ProductAlgorithmExamples.taskGoalExecutionOrder());
    }

    @Test
    void findsOnlyTheActualCalendarOverlapInTheProductBackedExample() {
        List<IntervalConflict<String>> conflicts = ProductAlgorithmExamples.calendarConflicts();

        assertEquals(1, conflicts.size());
        assertEquals("event:planning", conflicts.getFirst().earlier().value());
        assertEquals("block:review", conflicts.getFirst().later().value());
    }

    @Test
    void ranksTheProductBackedFocusQueueDeterministically() {
        List<PrioritizedItem<String>> ranked = ProductAlgorithmExamples.calendarFocusQueue();

        assertEquals(
                List.of("task:prepare", "task:review", "task:inbox-zero"),
                ranked.stream().map(PrioritizedItem::value).toList());
    }
}
