package com.lifeos.labs.algorithms.trees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedBinarySearchTreeTest {

    @Test
    void ordersUniqueValuesAndFindsThemWithoutRecursiveTraversal() {
        BoundedBinarySearchTree<Integer> tree = new BoundedBinarySearchTree<>(4);

        assertTrue(tree.add(4));
        assertTrue(tree.add(2));
        assertTrue(tree.add(8));
        assertTrue(tree.add(6));
        assertFalse(tree.add(4));

        assertEquals(List.of(2, 4, 6, 8), tree.inOrder());
        assertTrue(tree.contains(6));
        assertFalse(tree.contains(5));
        assertEquals(4, tree.size());
    }

    @Test
    void rejectsDistinctValuesAboveTheConfiguredNodeCap() {
        BoundedBinarySearchTree<Integer> tree = new BoundedBinarySearchTree<>(1);
        tree.add(1);

        assertThrows(AlgorithmInputException.class, () -> tree.add(2));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBinarySearchTree<>(0));
    }
}
