package com.lifeos.labs.algorithms.dynamicprogramming;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.Objects;

/**
 * Unicode code-point Levenshtein distance with explicit work and memory bounds.
 *
 * <p>A Document Vault search UI can use this to rank typo-tolerant suggestions after access
 * filtering. It uses the classic dynamic-programming recurrence in O(M * N) time and O(min(M, N))
 * memory, where M and N are code-point lengths. This is intentionally not a full-text search or
 * authorization mechanism.
 */
public final class BoundedLevenshteinDistance {

    private final int maxCodePointsPerInput;
    private final long maxCells;

    /** Creates a calculator with explicit per-input and dynamic-programming-cell limits. */
    public BoundedLevenshteinDistance(int maxCodePointsPerInput, long maxCells) {
        if (maxCodePointsPerInput < 1 || maxCells < 1) {
            throw new IllegalArgumentException("edit-distance bounds must be positive");
        }
        this.maxCodePointsPerInput = maxCodePointsPerInput;
        this.maxCells = maxCells;
    }

    /** Returns the minimum number of insertions, deletions, or substitutions between two strings. */
    public int distance(String first, String second) {
        int[] firstCodePoints = codePoints(first);
        int[] secondCodePoints = codePoints(second);
        ensureWorkBound(firstCodePoints.length, secondCodePoints.length);
        if (firstCodePoints.length < secondCodePoints.length) {
            return calculate(firstCodePoints, secondCodePoints);
        }
        return calculate(secondCodePoints, firstCodePoints);
    }

    private int[] codePoints(String value) {
        Objects.requireNonNull(value, "edit-distance input must not be null");
        int count = value.codePointCount(0, value.length());
        if (count > maxCodePointsPerInput) {
            throw new AlgorithmInputException("edit-distance input exceeds the configured limit");
        }
        return value.codePoints().toArray();
    }

    private void ensureWorkBound(int firstLength, int secondLength) {
        long cells;
        try {
            cells = Math.multiplyExact((long) firstLength + 1, (long) secondLength + 1);
        } catch (ArithmeticException exception) {
            throw new AlgorithmInputException("edit-distance work exceeds the configured limit");
        }
        if (cells > maxCells) {
            throw new AlgorithmInputException("edit-distance work exceeds the configured limit");
        }
    }

    private int calculate(int[] shorter, int[] longer) {
        int[] previous = new int[shorter.length + 1];
        int[] current = new int[shorter.length + 1];
        for (int index = 0; index <= shorter.length; index++) {
            previous[index] = index;
        }
        for (int longerIndex = 1; longerIndex <= longer.length; longerIndex++) {
            current[0] = longerIndex;
            for (int shorterIndex = 1; shorterIndex <= shorter.length; shorterIndex++) {
                int replacementCost = longer[longerIndex - 1] == shorter[shorterIndex - 1] ? 0 : 1;
                current[shorterIndex] = Math.min(
                        Math.min(previous[shorterIndex] + 1, current[shorterIndex - 1] + 1),
                        previous[shorterIndex - 1] + replacementCost);
            }
            int[] completed = previous;
            previous = current;
            current = completed;
        }
        return previous[shorter.length];
    }
}
