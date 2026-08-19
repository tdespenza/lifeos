package com.lifeos.documentvault.api;

import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.service.DocumentSearchResult;
import java.time.Instant;
import java.util.UUID;

/** Search result exposes relevance and source metadata but never an object-store locator. */
public record DocumentSearchResultResponse(
        UUID id,
        String title,
        DocumentSource source,
        Instant documentTimestamp,
        long version,
        int relevance) {

    static DocumentSearchResultResponse from(DocumentSearchResult result) {
        return new DocumentSearchResultResponse(
                result.id(),
                result.title(),
                result.source(),
                result.documentTimestamp(),
                result.version(),
                result.relevance());
    }
}
