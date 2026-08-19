package com.lifeos.media.api;

import com.lifeos.media.domain.MediaAsset;
import com.lifeos.media.domain.MediaAssetStatus;
import java.time.Instant;
import java.util.UUID;

/** Public media metadata; source object references and checksums remain private implementation facts. */
public record MediaAssetResponse(
        UUID id,
        String title,
        MediaAssetStatus status,
        String contentType,
        Long contentLength,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static MediaAssetResponse from(MediaAsset asset) {
        return new MediaAssetResponse(
                asset.getId(),
                asset.getTitle(),
                asset.getStatus(),
                asset.getContentType(),
                asset.getContentLength(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                asset.getVersion());
    }
}
