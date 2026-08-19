package com.lifeos.documentvault.proof;

import java.time.Instant;
import java.util.UUID;

/** Public-safe proof request state; no object-store reference or raw content is exposed. */
public record DocumentProofRequestResponse(
        UUID requestId,
        UUID documentId,
        long documentVersion,
        String checksumSha256,
        DocumentProofRequestState state,
        Instant requestedAt,
        boolean replayed) {

    static DocumentProofRequestResponse from(DocumentProofRequest request, boolean replayed) {
        return new DocumentProofRequestResponse(
                request.getId(),
                request.getDocumentId(),
                request.getDocumentVersion(),
                request.getChecksumSha256(),
                request.getState(),
                request.getCreatedAt(),
                replayed);
    }
}
