package com.lifeos.labs.algorithms.strings;

import com.lifeos.algorithms.AlgorithmInputException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic bounded Unicode tokenization for a Document Vault search projection.
 *
 * <p>The tokenizer applies NFKC normalization and {@link Locale#ROOT} lower-casing before
 * retaining letter-or-digit code points. It runs in O(N) time and O(N) bounded result space. It is
 * not a security authorization filter, stemmer, language detector, or full-text index.
 */
public final class BoundedNormalizedTokenizer {

    private final int maxInputCharacters;
    private final int maxTokens;
    private final int maxTokenCodePoints;

    /** Creates a tokenizer with explicit input, token-count, and token-length bounds. */
    public BoundedNormalizedTokenizer(int maxInputCharacters, int maxTokens, int maxTokenCodePoints) {
        if (maxInputCharacters < 1 || maxTokens < 1 || maxTokenCodePoints < 1) {
            throw new IllegalArgumentException("tokenization bounds must be positive");
        }
        this.maxInputCharacters = maxInputCharacters;
        this.maxTokens = maxTokens;
        this.maxTokenCodePoints = maxTokenCodePoints;
    }

    /**
     * Returns immutable normalized tokens, rejecting rather than truncating over-bound input.
     *
     * @param input caller-authorized metadata or query text
     * @return stable normalized tokens in first-seen order
     */
    public List<String> tokenize(String input) {
        if (input == null) {
            throw new AlgorithmInputException("tokenization input is required");
        }
        if (input.length() > maxInputCharacters) {
            throw new AlgorithmInputException("tokenization input exceeds the configured limit");
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int tokenCodePoints = 0;
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (++tokenCodePoints > maxTokenCodePoints) {
                    throw new AlgorithmInputException("token length exceeds the configured limit");
                }
                token.appendCodePoint(codePoint);
            } else {
                appendToken(tokens, token);
                tokenCodePoints = 0;
            }
        }
        appendToken(tokens, token);
        return List.copyOf(tokens);
    }

    private void appendToken(List<String> tokens, StringBuilder token) {
        if (token.isEmpty()) {
            return;
        }
        if (tokens.size() >= maxTokens) {
            throw new AlgorithmInputException("token count exceeds the configured limit");
        }
        tokens.add(token.toString());
        token.setLength(0);
    }
}
