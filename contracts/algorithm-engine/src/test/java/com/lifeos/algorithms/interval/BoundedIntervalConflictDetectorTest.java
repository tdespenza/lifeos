package com.lifeos.algorithms.interval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedIntervalConflictDetectorTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private final BoundedIntervalConflictDetector detector = new BoundedIntervalConflictDetector(10, 10);

    @Test
    void findsOverlapsButNotAdjacentIntervalsInDeterministicOrder() {
        TimeInterval<String> first = interval("first", 0, 30);
        TimeInterval<String> second = interval("second", 10, 20);
        TimeInterval<String> third = interval("third", 30, 45);
        TimeInterval<String> fourth = interval("fourth", 15, 40);

        List<IntervalConflict<String>> conflicts = detector.findConflicts(List.of(first, second, third, fourth));

        assertEquals(
                List.of(
                        new IntervalConflict<>(first, second),
                        new IntervalConflict<>(first, fourth),
                        new IntervalConflict<>(second, fourth),
                        new IntervalConflict<>(fourth, third)),
                conflicts);
    }

    @Test
    void rejectsOversizedOutputAndMalformedCollections() {
        BoundedIntervalConflictDetector bounded = new BoundedIntervalConflictDetector(5, 1);
        assertThrows(
                AlgorithmInputException.class,
                () -> bounded.findConflicts(List.of(interval("a", 0, 10), interval("b", 1, 11), interval("c", 2, 12))));
        assertThrows(AlgorithmInputException.class, () -> detector.findConflicts(null));
        assertThrows(
                AlgorithmInputException.class,
                () -> detector.findConflicts(Arrays.asList(interval("a", 0, 1), null)));
        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedIntervalConflictDetector(1, 1)
                        .findConflicts(List.of(interval("a", 0, 1), interval("b", 2, 3))));
    }

    @Test
    void allowsZeroConflictLimitWhenNoConflictsExistAndThrowsWhenOneIsFound() {
        BoundedIntervalConflictDetector zeroConflictLimit = new BoundedIntervalConflictDetector(5, 0);

        assertEquals(
                List.of(),
                zeroConflictLimit.findConflicts(List.of(interval("a", 0, 1), interval("b", 1, 2))));
        assertThrows(
                AlgorithmInputException.class,
                () -> zeroConflictLimit.findConflicts(List.of(interval("a", 0, 2), interval("b", 1, 3))));
    }

    private static TimeInterval<String> interval(String value, int startSeconds, int endSeconds) {
        return new TimeInterval<>(
                value, BASE.plusSeconds(startSeconds), BASE.plusSeconds(endSeconds));
    }
}
