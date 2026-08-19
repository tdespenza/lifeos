package com.lifeos.labs.algorithms.unionfind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.algorithms.AlgorithmInputException;
import org.junit.jupiter.api.Test;

class BoundedDisjointSetTest {

    @Test
    void ConnectsComponentsUsingPathCompressionAndRank() {
        BoundedDisjointSet<String> relationships = new BoundedDisjointSet<>(4);
        relationships.add("a");
        relationships.add("b");
        relationships.add("c");

        assertTrue(relationships.union("a", "b"));
        assertTrue(relationships.union("b", "c"));
        assertTrue(relationships.connected("a", "c"));
        assertFalse(relationships.union("a", "c"));
        assertEquals(1, relationships.componentCount());
    }

    @Test
    void rejectsUnknownValuesAndOverBoundSets() {
        BoundedDisjointSet<String> relationships = new BoundedDisjointSet<>(1);
        relationships.add("a");

        assertThrows(AlgorithmInputException.class, () -> relationships.add("b"));
        assertThrows(AlgorithmInputException.class, () -> relationships.connected("a", "missing"));
    }
}
