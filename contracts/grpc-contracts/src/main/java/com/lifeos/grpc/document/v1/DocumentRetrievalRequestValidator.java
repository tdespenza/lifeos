package com.lifeos.grpc.document.v1;

/** Enforces bounded document-excerpt request and response sizes. */
public final class DocumentRetrievalRequestValidator {

    /** Default response budget when the request leaves maximum_characters at zero. */
    public static final int DEFAULT_MAXIMUM_CHARACTERS = 16_384;

    /** Maximum request and combined-response character budget. */
    public static final int MAXIMUM_CHARACTERS = 64_000;

    private DocumentRetrievalRequestValidator() {
    }

    /**
     * Resolves and validates the request's maximum character budget.
     *
     * <p>The budget counts Unicode code points across all returned excerpt text, not separately
     * per excerpt. A zero request selects the bounded default.
     *
     * @param request request to validate
     * @return effective combined-response character budget
     * @throws IllegalArgumentException when the request is null or exceeds the maximum
     */
    public static int effectiveMaximumCharacters(GetAuthorizedExcerptRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return effectiveMaximumCharacters(request.getMaximumCharacters());
    }

    /**
     * Resolves a raw maximum character budget.
     *
     * @param requestedMaximumCharacters requested budget, where zero selects the default
     * @return effective bounded budget
     * @throws IllegalArgumentException when the requested budget exceeds the maximum
     */
    public static int effectiveMaximumCharacters(int requestedMaximumCharacters) {
        if (requestedMaximumCharacters < 0 || requestedMaximumCharacters > MAXIMUM_CHARACTERS) {
            throw new IllegalArgumentException("maximum_characters must not exceed 64000");
        }
        return requestedMaximumCharacters == 0
                ? DEFAULT_MAXIMUM_CHARACTERS
                : requestedMaximumCharacters;
    }

    /**
     * Validates that the combined text returned by the document service fits the request budget.
     *
     * @param response response to validate
     * @param request originating request
     * @throws IllegalArgumentException when the response is null or exceeds the budget
     */
    public static void validateResponse(
            GetAuthorizedExcerptResponse response,
            GetAuthorizedExcerptRequest request) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        int maximumCharacters = effectiveMaximumCharacters(request);
        long totalCharacters = response.getExcerptsList().stream()
                .mapToLong(excerpt -> excerpt.getText().codePointCount(0, excerpt.getText().length()))
                .sum();
        if (totalCharacters > maximumCharacters) {
            throw new IllegalArgumentException("combined excerpt text exceeds maximum_characters");
        }
    }
}
