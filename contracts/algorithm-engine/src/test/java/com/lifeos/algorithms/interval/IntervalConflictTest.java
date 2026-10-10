package com.lifeos.algorithms.interval;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IntervalConflictTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsAdjacentIntervals() {
        TimeInterval<String> earlier = interval("first", 0, 10);
        TimeInterval<String> later = interval("second", 10, 20);

        assertThrows(IllegalArgumentException.class, () -> new IntervalConflict<>(earlier, later));
    }

    @Test
    void rejectsDisjointIntervals() {
        TimeInterval<String> earlier = interval("first", 0, 5);
        TimeInterval<String> later = interval("second", 10, 20);

        assertThrows(IllegalArgumentException.class, () -> new IntervalConflict<>(earlier, later));
    }

    @Test
    void rejectsAReversedOverlappingPair() {
        TimeInterval<String> earlier = interval("first", 0, 10);
        TimeInterval<String> later = interval("second", 5, 15);

        assertThrows(IllegalArgumentException.class, () -> new IntervalConflict<>(later, earlier));
    }

    private static TimeInterval<String> interval(String value, int startSeconds, int endSeconds) {
        return new TimeInterval<>(value, BASE.plusSeconds(startSeconds), BASE.plusSeconds(endSeconds));
    }
}
