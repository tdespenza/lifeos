package com.lifeos.media.service;

import com.lifeos.media.authorization.MediaSubject;
import java.time.Instant;
import java.util.UUID;

/** Narrow workload-authenticated command port for optional digest-only session anchoring. */
public interface MediaTrustLedgerClient {

    AnchorResult anchorSessionSummary(
            MediaSubject subject,
            UUID artifactId,
            long artifactVersion,
            String digestSha256,
            String idempotencyKey);

    record AnchorResult(
            UUID requestId,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String digestSha256,
            String state,
            String transactionHash,
            Long blockNumber,
            Instant updatedAt) {
    }
}
