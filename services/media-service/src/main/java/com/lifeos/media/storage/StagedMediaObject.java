package com.lifeos.media.storage;

import java.util.Objects;
import java.util.UUID;

/** Verified temporary source object; only generated UUIDs address staging paths. */
public record StagedMediaObject(UUID stagingId, String checksumSha256, long contentLength, MediaContentType contentType) {

    public StagedMediaObject {
        Objects.requireNonNull(stagingId, "stagingId must not be null");
        if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a digest");
        }
        if (contentLength < 1) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        Objects.requireNonNull(contentType, "contentType must not be null");
    }
}
