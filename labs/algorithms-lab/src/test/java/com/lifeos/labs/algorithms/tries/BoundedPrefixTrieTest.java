package com.lifeos.labs.algorithms.tries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedPrefixTrieTest {

    @Test
    void suggestsOnlyStoredTermsInDeterministicLexicographicOrder() {
        BoundedPrefixTrie trie = new BoundedPrefixTrie(20, 12);
        trie.add("calendar");
        trie.add("call");
        trie.add("camera");

        assertEquals(List.of("calendar", "call"), trie.suggest("cal", 2));
        assertTrue(trie.contains("camera"));
        assertFalse(trie.contains("cam"));
        assertFalse(trie.add("calendar"));
    }

    @Test
    void rejectsOverBoundWordsNodesAndSuggestionLimits() {
        BoundedPrefixTrie trie = new BoundedPrefixTrie(2, 3);

        trie.add("a");
        assertThrows(AlgorithmInputException.class, () -> trie.add("ab"));
        assertThrows(AlgorithmInputException.class, () -> trie.add("long"));
        assertThrows(AlgorithmInputException.class, () -> trie.suggest("a", 3));
    }
}
