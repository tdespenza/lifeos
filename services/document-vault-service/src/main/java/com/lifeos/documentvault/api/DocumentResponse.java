package com.lifeos.documentvault.api;

import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.service.DocumentView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public metadata representation; storage references and file bytes are intentionally absent. */
public record DocumentResponse(
        UUID id,
        String title,
        List<String> tags,
        Instant documentTimestamp,
        DocumentSource source,
        DocumentClassification classification,
        String contentType,
        long contentLength,
        String checksumSha256,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static DocumentResponse from(DocumentView view) {
        return new DocumentResponse(
                view.id(),
                view.title(),
                view.tags(),
                view.documentTimestamp(),
                view.source(),
                view.classification(),
                view.contentType(),
                view.contentLength(),
                view.checksumSha256(),
                view.version(),
                view.createdAt(),
                view.updatedAt());
    }
}
