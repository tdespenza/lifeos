package com.lifeos.documentvault.api;

import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentMetadata;
import com.lifeos.documentvault.domain.DocumentSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** JSON metadata mutation; all normalization and tag validation remain in the domain value object. */
public record UpdateDocumentMetadataRequest(
        @NotBlank @Size(max = 255) String title,
        List<String> tags,
        Instant documentTimestamp,
        @NotNull DocumentSource source,
        @NotNull DocumentClassification classification) {

    DocumentMetadata toMetadata() {
        return new DocumentMetadata(title, tags, documentTimestamp, source, classification);
    }
}
