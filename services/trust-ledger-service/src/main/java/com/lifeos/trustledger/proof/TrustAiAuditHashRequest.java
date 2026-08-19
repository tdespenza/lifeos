package com.lifeos.trustledger.proof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Privacy-minimized AI audit commitment projection. It stores no prompt, completion, or provider
 * credential and deliberately remains pending until a reviewed external anchor workflow exists.
 */
@Entity
@Table(
        name = "trust_ai_audit_hash_request",
        indexes = @Index(name = "idx_trust_ai_audit_hash_owner", columnList = "owner_account_id, received_at"))
public class TrustAiAuditHashRequest {

    @Id
    @Column(name = "audit_event_id")
    private UUID auditEventId;

    @Column(name = "owner_account_id")
    private UUID ownerAccountId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "audit_hash_sha256", nullable = false, length = 64)
    private String auditHashSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private TrustAiAuditHashState state;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected TrustAiAuditHashRequest() {
        // required by JPA
    }

    public TrustAiAuditHashRequest(
            UUID auditEventId,
            UUID ownerAccountId,
            UUID conversationId,
            String auditHashSha256,
            Instant receivedAt) {
        this.auditEventId = Objects.requireNonNull(auditEventId, "auditEventId must not be null");
        this.ownerAccountId = ownerAccountId;
        this.conversationId = conversationId;
        if (auditHashSha256 == null || !auditHashSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("auditHashSha256 must be a SHA-256 digest");
        }
        this.auditHashSha256 = auditHashSha256;
        state = TrustAiAuditHashState.PENDING_EXTERNAL_ANCHOR;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }

    public UUID getAuditEventId() {
        return auditEventId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getAuditHashSha256() {
        return auditHashSha256;
    }

    public TrustAiAuditHashState getState() {
        return state;
    }
}
