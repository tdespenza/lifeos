package com.lifeos.algorithms.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.algorithms.AlgorithmInputException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedPriorityRankerTest {

    private final BoundedPriorityRanker ranker = new BoundedPriorityRanker(10);

    @Test
    void ranksByPriorityThenDeadlineThenFirstSeenOrder() {
        List<PrioritizedItem<String>> result = ranker.rank(
                List.of(
                        new PrioritizedItem<>("low", 1, Instant.parse("2026-01-03T00:00:00Z")),
                        new PrioritizedItem<>("later", 5, Instant.parse("2026-01-03T00:00:00Z")),
                        new PrioritizedItem<>("earlier", 5, Instant.parse("2026-01-02T00:00:00Z")),
                        new PrioritizedItem<>("same-deadline-first", 5, Instant.parse("2026-01-02T00:00:00Z")),
                        new PrioritizedItem<>("no-deadline", 5, null)),
                5);

        assertEquals(
                List.of("earlier", "same-deadline-first", "later", "no-deadline", "low"),
                result.stream().map(PrioritizedItem::value).toList());
    }

    @Test
    void returnsOnlyTheRequestedRankedPrefix() {
        List<PrioritizedItem<String>> result = ranker.rank(
                List.of(
                        new PrioritizedItem<>("highest", 2, null),
                        new PrioritizedItem<>("lower", 1, null)),
                1);

        assertEquals(List.of("highest"), result.stream().map(PrioritizedItem::value).toList());
    }

    @Test
    void returnsAnEmptyPrefixForAnEmptyCandidateCollection() {
        assertEquals(List.of(), ranker.rank(List.of(), 1));
    }

    @Test
    void rejectsInvalidLimitsAndMalformedOrOversizedCollections() {
        assertThrows(AlgorithmInputException.class, () -> ranker.rank(null, 1));
        assertThrows(AlgorithmInputException.class, () -> ranker.rank(List.of(), 0));
        assertThrows(AlgorithmInputException.class, () -> ranker.rank(List.of(), 11));
        assertThrows(
                AlgorithmInputException.class,
                () -> ranker.rank(Arrays.asList(new PrioritizedItem<>("valid", 1, null), null), 1));
        assertThrows(
                AlgorithmInputException.class,
                () -> new BoundedPriorityRanker(1)
                        .rank(List.of(new PrioritizedItem<>("a", 1, null), new PrioritizedItem<>("b", 1, null)), 1));
    }

    @Test
    void rejectsNonPositiveCandidateLimit() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedPriorityRanker(0));
    }

    @Test
    void returnsAnImmutableResult() {
        List<PrioritizedItem<String>> result =
                ranker.rank(List.of(new PrioritizedItem<>("only", 1, null)), 1);

        assertThrows(
                UnsupportedOperationException.class,
                () -> result.add(new PrioritizedItem<>("extra", 1, null)));
    }
}
