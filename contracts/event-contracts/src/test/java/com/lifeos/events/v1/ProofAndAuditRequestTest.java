package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProofAndAuditRequestTest {

    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void acceptsValidDocumentProofRequest() {
        assertDoesNotThrow(() -> new DocumentProofRequestedV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "tenant-a",
                1,
                CHECKSUM));
    }

    @Test
    void rejectsInvalidDocumentProofRequestFields() {
        UUID requestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                null, documentId, ownerAccountId, "tenant-a", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                requestId, null, ownerAccountId, "tenant-a", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                requestId, documentId, null, "tenant-a", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                requestId, documentId, ownerAccountId, " ", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                requestId, documentId, ownerAccountId, "tenant-a", -1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                requestId, documentId, ownerAccountId, "tenant-a", 1, "not-a-checksum"));
    }

    @Test
    void acceptsValidAiAuditHashRequest() {
        assertDoesNotThrow(() -> new AiAuditHashRequestedV1(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CHECKSUM));
    }

    @Test
    void rejectsInvalidAiAuditHashRequestFields() {
        UUID auditEventId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                null, ownerAccountId, conversationId, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                auditEventId, null, conversationId, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                auditEventId, ownerAccountId, null, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                auditEventId, ownerAccountId, conversationId, "not-a-checksum"));
    }
}
