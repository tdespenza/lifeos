package com.lifeos.labs.algorithms.dynamicprogramming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import org.junit.jupiter.api.Test;

class BoundedLevenshteinDistanceTest {

    @Test
    void calculatesUnicodeAwareDistancesUsingOnlyOneDynamicProgrammingRowPair() {
        BoundedLevenshteinDistance distance = new BoundedLevenshteinDistance(16, 300);

        assertEquals(3, distance.distance("kitten", "sitting"));
        assertEquals(1, distance.distance("café", "cafe"));
        assertEquals(0, distance.distance("calendar", "calendar"));
    }

    @Test
    void rejectsInputsAndWorkThatExceedConfiguredBounds() {
        BoundedLevenshteinDistance inputBound = new BoundedLevenshteinDistance(3, 20);
        BoundedLevenshteinDistance workBound = new BoundedLevenshteinDistance(8, 8);

        assertThrows(AlgorithmInputException.class, () -> inputBound.distance("four", "two"));
        assertThrows(AlgorithmInputException.class, () -> workBound.distance("abcd", "efgh"));
        assertThrows(NullPointerException.class, () -> inputBound.distance(null, "two"));
    }
}
