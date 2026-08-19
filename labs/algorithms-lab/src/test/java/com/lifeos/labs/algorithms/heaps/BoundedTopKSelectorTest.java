package com.lifeos.labs.algorithms.heaps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedTopKSelectorTest {

    @Test
    void retainsTheBestItemsAndPreservesFirstSeenTies() {
        BoundedTopKSelector<Integer> selector = new BoundedTopKSelector<>(6);

        assertEquals(
                List.of(9, 7, 7),
                selector.select(List.of(4, 7, 9, 7, 2, 6), 3, Comparator.reverseOrder()));
    }

    @Test
    void rejectsInvalidBoundsAndOversizedInput() {
        BoundedTopKSelector<Integer> selector = new BoundedTopKSelector<>(2);

        assertThrows(
                AlgorithmInputException.class,
                () -> selector.select(List.of(1), 0, Comparator.reverseOrder()));
        assertThrows(
                AlgorithmInputException.class,
                () -> selector.select(List.of(1, 2, 3), 1, Comparator.reverseOrder()));
    }
}
