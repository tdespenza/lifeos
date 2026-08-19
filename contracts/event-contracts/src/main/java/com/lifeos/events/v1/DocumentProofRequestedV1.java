package com.lifeos.events.v1;

import java.util.UUID;

/** Privacy-minimized proof command; it contains no filename, title, object reference, or content. */
public record DocumentProofRequestedV1(
        UUID requestId,
        UUID documentId,
        UUID ownerAccountId,
        String tenantId,
        long documentVersion,
        String checksumSha256) {

    public DocumentProofRequestedV1 {
        if (requestId == null || documentId == null || ownerAccountId == null) {
            throw new IllegalArgumentException("proof identifiers must not be null");
        }
        CloudEventV1.requireText(tenantId, "tenantId", 255);
        if (documentVersion < 0) {
            throw new IllegalArgumentException("documentVersion must not be negative");
        }
        if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a SHA-256 digest");
        }
    }
}
