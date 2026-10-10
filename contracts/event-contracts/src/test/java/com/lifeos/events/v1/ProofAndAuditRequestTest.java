package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProofAndAuditRequestTest {

    private static final String CHECKSUM = "a".repeat(64);
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OWNER_ACCOUNT_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID AUDIT_EVENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000005");

    @Test
    void acceptsValidDocumentProofRequest() {
        assertDoesNotThrow(() -> new DocumentProofRequestedV1(
                REQUEST_ID,
                DOCUMENT_ID,
                OWNER_ACCOUNT_ID,
                "tenant-a",
                1,
                CHECKSUM));
        assertDoesNotThrow(() -> new DocumentProofRequestedV1(
                REQUEST_ID,
                DOCUMENT_ID,
                OWNER_ACCOUNT_ID,
                "tenant-a",
                0,
                CHECKSUM));
    }

    @Test
    void rejectsInvalidDocumentProofRequestFields() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                null, DOCUMENT_ID, OWNER_ACCOUNT_ID, "tenant-a", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, null, OWNER_ACCOUNT_ID, "tenant-a", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, null, "tenant-a", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, OWNER_ACCOUNT_ID, " ", 1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, OWNER_ACCOUNT_ID, "tenant-a", -1, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, OWNER_ACCOUNT_ID, "tenant-a", 1, "not-a-checksum"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, OWNER_ACCOUNT_ID, "tenant-a", 1, null));
    }

    @Test
    void acceptsValidAiAuditHashRequest() {
        assertDoesNotThrow(() -> new AiAuditHashRequestedV1(
                AUDIT_EVENT_ID, OWNER_ACCOUNT_ID, CONVERSATION_ID, CHECKSUM));
    }

    @Test
    void rejectsInvalidAiAuditHashRequestFields() {
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                null, OWNER_ACCOUNT_ID, CONVERSATION_ID, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                AUDIT_EVENT_ID, null, CONVERSATION_ID, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                AUDIT_EVENT_ID, OWNER_ACCOUNT_ID, null, CHECKSUM));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                AUDIT_EVENT_ID, OWNER_ACCOUNT_ID, CONVERSATION_ID, "not-a-checksum"));
        assertThrows(IllegalArgumentException.class, () -> new AiAuditHashRequestedV1(
                AUDIT_EVENT_ID, OWNER_ACCOUNT_ID, CONVERSATION_ID, null));
    }

    @Test
    void acceptsTheMaximumDocumentProofTenantLength() {
        assertDoesNotThrow(() -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, OWNER_ACCOUNT_ID, "t".repeat(255), 1, CHECKSUM));
    }

    @Test
    void rejectsAnOverlongDocumentProofTenant() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentProofRequestedV1(
                REQUEST_ID, DOCUMENT_ID, OWNER_ACCOUNT_ID, "t".repeat(256), 1, CHECKSUM));
    }
}
