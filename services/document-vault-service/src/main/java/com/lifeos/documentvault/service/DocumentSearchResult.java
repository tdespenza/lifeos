package com.lifeos.documentvault.service;

import com.lifeos.documentvault.domain.DocumentSource;
import java.time.Instant;
import java.util.UUID;

/** Minimal deterministic search projection; object references and file bytes cannot leak here. */
public record DocumentSearchResult(
        UUID id,
        String title,
        DocumentSource source,
        Instant documentTimestamp,
        long version,
        int relevance) {
}
