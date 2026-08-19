package com.lifeos.documentvault.service;

import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.domain.VaultDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable public-safe document snapshot; it never contains the object-store reference. */
public record DocumentView(
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

    public static DocumentView from(VaultDocument document) {
        return new DocumentView(
                document.getId(),
                document.getTitle(),
                document.getTags(),
                document.getDocumentTimestamp(),
                document.getSource(),
                document.getClassification(),
                document.getContentType(),
                document.getContentLength(),
                document.getChecksumSha256(),
                document.getVersion(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
