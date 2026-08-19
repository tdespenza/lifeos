package com.lifeos.labs.algorithms.greedy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import com.lifeos.algorithms.interval.TimeInterval;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreedyIntervalSelectorTest {

    @Test
    void choosesAnOptimalCompatibleSetAndTreatsAdjacentSlotsAsCompatible() {
        Instant start = Instant.parse("2026-08-18T09:00:00Z");
        GreedyIntervalSelector selector = new GreedyIntervalSelector(5);

        List<TimeInterval<String>> selected = selector.select(List.of(
                interval("long", start, 6_000),
                interval("first", start, 1_800),
                interval("second", start.plusSeconds(1_800), 1_800),
                interval("third", start.plusSeconds(3_600), 1_800)));

        assertEquals(List.of("first", "second", "third"), selected.stream().map(TimeInterval::value).toList());
    }

    @Test
    void rejectsNullAndOverBoundIntervals() {
        GreedyIntervalSelector selector = new GreedyIntervalSelector(1);
        Instant start = Instant.parse("2026-08-18T09:00:00Z");

        assertThrows(AlgorithmInputException.class, () -> selector.select(null));
        assertThrows(
                AlgorithmInputException.class,
                () -> selector.select(List.of(interval("one", start, 1), interval("two", start, 2))));
    }

    private static TimeInterval<String> interval(String value, Instant start, long durationSeconds) {
        return new TimeInterval<>(value, start, start.plusSeconds(durationSeconds));
    }
}
