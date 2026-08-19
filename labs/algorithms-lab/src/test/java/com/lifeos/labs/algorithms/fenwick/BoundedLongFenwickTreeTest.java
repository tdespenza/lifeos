package com.lifeos.labs.algorithms.fenwick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import org.junit.jupiter.api.Test;

class BoundedLongFenwickTreeTest {

    @Test
    void appliesDeltasAndAnswersPrefixAndRangeSums() {
        BoundedLongFenwickTree tree = new BoundedLongFenwickTree(5, 10);
        tree.add(0, 100);
        tree.add(2, 30);
        tree.add(4, -10);

        assertEquals(130, tree.prefixSum(3));
        assertEquals(20, tree.rangeSum(2, 5));
        assertEquals(5, tree.size());
    }

    @Test
    void rejectsInvalidSizesAndBounds() {
        assertThrows(AlgorithmInputException.class, () -> new BoundedLongFenwickTree(0, 2));
        BoundedLongFenwickTree tree = new BoundedLongFenwickTree(1, 2);
        assertThrows(AlgorithmInputException.class, () -> tree.add(1, 1));
        assertThrows(AlgorithmInputException.class, () -> tree.rangeSum(1, 0));
    }
}
