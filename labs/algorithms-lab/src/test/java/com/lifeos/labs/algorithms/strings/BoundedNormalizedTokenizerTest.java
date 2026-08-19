package com.lifeos.labs.algorithms.strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedNormalizedTokenizerTest {

    private final BoundedNormalizedTokenizer tokenizer = new BoundedNormalizedTokenizer(64, 4, 8);

    @Test
    void normalizesUnicodeAndSeparatesPunctuation() {
        assertEquals(List.of("café", "plan", "2026"), tokenizer.tokenize("CAFÉ—Plan 2026!"));
    }

    @Test
    void rejectsOverBoundTokenCountsAndLengthsWithoutTruncating() {
        assertThrows(AlgorithmInputException.class, () -> tokenizer.tokenize("a b c d e"));
        assertThrows(AlgorithmInputException.class, () -> tokenizer.tokenize("ninechars"));
        assertThrows(AlgorithmInputException.class, () -> tokenizer.tokenize(null));
    }
}
