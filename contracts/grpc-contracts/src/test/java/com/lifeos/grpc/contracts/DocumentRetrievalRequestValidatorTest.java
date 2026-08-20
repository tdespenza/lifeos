package com.lifeos.grpc.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.grpc.document.v1.DocumentExcerpt;
import com.lifeos.grpc.document.v1.DocumentRetrievalRequestValidator;
import com.lifeos.grpc.document.v1.GetAuthorizedExcerptRequest;
import com.lifeos.grpc.document.v1.GetAuthorizedExcerptResponse;
import org.junit.jupiter.api.Test;

class DocumentRetrievalRequestValidatorTest {

    @Test
    void usesABoundedDefaultWhenMaximumCharactersIsZero() {
        GetAuthorizedExcerptRequest request = GetAuthorizedExcerptRequest.newBuilder().build();

        assertEquals(
                DocumentRetrievalRequestValidator.DEFAULT_MAXIMUM_CHARACTERS,
                DocumentRetrievalRequestValidator.effectiveMaximumCharacters(request));
    }

    @Test
    void rejectsARequestAboveTheMaximum() {
        GetAuthorizedExcerptRequest request = GetAuthorizedExcerptRequest.newBuilder()
                .setMaximumCharacters(DocumentRetrievalRequestValidator.MAXIMUM_CHARACTERS + 1)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentRetrievalRequestValidator.effectiveMaximumCharacters(request));
    }

    @Test
    void rejectsANegativeRequestBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentRetrievalRequestValidator.effectiveMaximumCharacters(-1));
    }

    @Test
    void rejectsCombinedExcerptTextAboveTheEffectiveBudget() {
        GetAuthorizedExcerptRequest request = GetAuthorizedExcerptRequest.newBuilder()
                .setMaximumCharacters(10)
                .build();
        GetAuthorizedExcerptResponse response = GetAuthorizedExcerptResponse.newBuilder()
                .addExcerpts(DocumentExcerpt.newBuilder().setText("123456").build())
                .addExcerpts(DocumentExcerpt.newBuilder().setText("78901").build())
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> DocumentRetrievalRequestValidator.validateResponse(response, request));
    }

    @Test
    void acceptsCombinedExcerptTextWithinTheEffectiveBudget() {
        GetAuthorizedExcerptRequest request = GetAuthorizedExcerptRequest.newBuilder()
                .setMaximumCharacters(10)
                .build();
        GetAuthorizedExcerptResponse response = GetAuthorizedExcerptResponse.newBuilder()
                .addExcerpts(DocumentExcerpt.newBuilder().setText("123456").build())
                .addExcerpts(DocumentExcerpt.newBuilder().setText("7890").build())
                .build();

        DocumentRetrievalRequestValidator.validateResponse(response, request);
    }
}
