package com.lifeos.labs.algorithms.bloom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.algorithms.AlgorithmInputException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedBloomFilterTest {

    @Test
    void hasNoFalseNegativeForAnInsertedDigestCandidate() {
        BoundedBloomFilter filter = new BoundedBloomFilter(1_024, 4, 64);
        byte[] digest = "document-digest".getBytes(StandardCharsets.UTF_8);

        filter.add(digest);

        assertTrue(filter.mightContain(digest));
        assertFalse(filter.mightContain("unseen".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnsafeBoundsAndValues() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBloomFilter(63, 1, 1));
        BoundedBloomFilter filter = new BoundedBloomFilter(64, 1, 2);
        assertThrows(AlgorithmInputException.class, () -> filter.add(new byte[0]));
        assertThrows(AlgorithmInputException.class, () -> filter.add(new byte[] {1, 2, 3}));
    }
}
