package com.lifeos.labs.algorithms.segmenttrees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import org.junit.jupiter.api.Test;

class BoundedLongRangeSumTreeTest {

    @Test
    void answersHalfOpenRangeSumsAndPointUpdates() {
        BoundedLongRangeSumTree tree = new BoundedLongRangeSumTree(new long[] {100, -20, 50, 70}, 8);

        assertEquals(200, tree.sum(0, 4));
        assertEquals(30, tree.sum(1, 3));
        tree.set(1, -40);
        assertEquals(180, tree.sum(0, 4));
        assertEquals(4, tree.size());
    }

    @Test
    void rejectsEmptyOverBoundAndInvalidRanges() {
        assertThrows(AlgorithmInputException.class, () -> new BoundedLongRangeSumTree(new long[0], 2));
        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedLongRangeSumTree(new long[] {1, 2, 3}, 2));

        BoundedLongRangeSumTree tree = new BoundedLongRangeSumTree(new long[] {1}, 2);
        assertThrows(AlgorithmInputException.class, () -> tree.sum(1, 0));
        assertThrows(AlgorithmInputException.class, () -> tree.set(1, 2));
    }
}
