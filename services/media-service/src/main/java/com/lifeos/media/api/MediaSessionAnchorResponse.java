package com.lifeos.media.api;

import com.lifeos.media.service.MediaTrustLedgerClient;
import java.time.Instant;
import java.util.UUID;

/** Public-safe digest-only session-summary anchor state. */
public record MediaSessionAnchorResponse(
        UUID requestId,
        UUID artifactId,
        long artifactVersion,
        String digestSha256,
        String state,
        String transactionHash,
        Long blockNumber,
        Instant updatedAt) {

    public static MediaSessionAnchorResponse from(MediaTrustLedgerClient.AnchorResult result) {
        return new MediaSessionAnchorResponse(
                result.requestId(), result.subjectId(), result.subjectVersion(), result.digestSha256(),
                result.state(), result.transactionHash(), result.blockNumber(), result.updatedAt());
    }
}
