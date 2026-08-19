package com.lifeos.assistant.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Durable, privacy-minimized record that a caller explicitly confirmed a side effect. */
@Entity
@Table(
        name = "assistant_tool_confirmation",
        indexes = @Index(
                name = "idx_assistant_tool_confirmation_conversation",
                columnList = "conversation_id, confirmed_at DESC, id"))
public class AssistantToolConfirmation {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 32)
    private AssistantToolOperation operation;

    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    protected AssistantToolConfirmation() {
        // required by JPA
    }

    AssistantToolConfirmation(
            UUID conversationId,
            UUID ownerAccountId,
            AssistantToolOperation operation,
            String idempotencyKeyHash,
            String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.conversationId = conversationId;
        this.ownerAccountId = ownerAccountId;
        this.operation = operation;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestFingerprint = requestFingerprint;
        this.confirmedAt = Instant.now();
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }
}
