package com.lifeos.labs.algorithms.hashmaps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundedFrequencyCounterTest {

    @Test
    void countsCategoriesWithoutChangingTheirFirstSeenOrder() {
        BoundedFrequencyCounter counter = new BoundedFrequencyCounter(6, 3);

        assertEquals(
                Map.of("food", 3, "travel", 2, "other", 1),
                counter.count(List.of("food", "travel", "food", "other", "travel", "food")));
        assertEquals(
                List.of("food", "travel", "other"),
                counter.count(List.of("food", "travel", "food", "other")).keySet().stream().toList());
    }

    @Test
    void rejectsOverBoundAndNullInput() {
        BoundedFrequencyCounter counter = new BoundedFrequencyCounter(2, 1);

        assertThrows(AlgorithmInputException.class, () -> counter.count(List.of("food", "travel")));
        assertThrows(AlgorithmInputException.class, () -> counter.count(List.of("food", "food", "food")));
        assertThrows(AlgorithmInputException.class, () -> counter.count(Arrays.asList("food", null)));
    }
}
