package com.lifeos.documentvault.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Validated editable metadata. Object bytes, references, checksums, and size are excluded. */
public record DocumentMetadata(
        String title,
        List<String> tags,
        Instant documentTimestamp,
        DocumentSource source,
        DocumentClassification classification) {

    public DocumentMetadata {
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new IllegalArgumentException("title must be non-blank and at most 255 characters");
        }
        title = title.trim();
        tags = DocumentTags.decode(DocumentTags.encode(tags));
        source = Objects.requireNonNull(source, "source must not be null");
        classification = Objects.requireNonNull(classification, "classification must not be null");
    }

    public String encodedTags() {
        return DocumentTags.encode(tags);
    }
}
