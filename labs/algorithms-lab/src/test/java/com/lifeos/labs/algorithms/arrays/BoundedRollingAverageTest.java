package com.lifeos.labs.algorithms.arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundedRollingAverageTest {

    @Test
    void replacesTheOldestObservationAtTheFixedWindowBoundary() {
        BoundedRollingAverage average = new BoundedRollingAverage(3);

        assertFalse(average.average().isPresent());
        average.add(100);
        average.add(200);
        average.add(400);
        average.add(500);

        assertTrue(average.average().isPresent());
        assertEquals(1_100.0 / 3, average.average().getAsDouble());
        assertEquals(3, average.size());
        assertEquals(3, average.capacity());
    }

    @Test
    void rejectsAZeroCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedRollingAverage(0));
    }
}
