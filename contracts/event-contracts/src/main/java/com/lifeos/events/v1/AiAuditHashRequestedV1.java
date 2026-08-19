package com.lifeos.events.v1;

import java.util.UUID;

/**
 * Privacy-minimized AI audit command. It contains a reproducible commitment only; prompt,
 * completion, provider credentials, and retrieved document content are deliberately absent.
 */
public record AiAuditHashRequestedV1(
        UUID auditEventId,
        UUID ownerAccountId,
        UUID conversationId,
        String auditHashSha256) {

    public AiAuditHashRequestedV1 {
        if (auditEventId == null) {
            throw new IllegalArgumentException("audit event id must not be null");
        }
        if (auditHashSha256 == null || !auditHashSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("auditHashSha256 must be a SHA-256 digest");
        }
    }
}
