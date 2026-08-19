package com.lifeos.documentvault.storage;

import java.util.Objects;
import java.util.UUID;

/** Opaque staging token and verified content facts; filesystem paths never leave the store package. */
public record StagedDocumentObject(
        UUID stagingId,
        String checksumSha256,
        long contentLength,
        DocumentContentType contentType,
        String searchableText) {

    /** Compatibility constructor for object-store adapters that do not extract searchable text. */
    public StagedDocumentObject(
            UUID stagingId, String checksumSha256, long contentLength, DocumentContentType contentType) {
        this(stagingId, checksumSha256, contentLength, contentType, "");
    }

    public StagedDocumentObject {
        Objects.requireNonNull(stagingId, "stagingId must not be null");
        if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a SHA-256 digest");
        }
        if (contentLength < 1) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        Objects.requireNonNull(contentType, "contentType must not be null");
        if (searchableText == null || searchableText.length() > 65_536) {
            throw new IllegalArgumentException("searchableText must be bounded");
        }
    }
}
