package com.lifeos.assistant.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped conversation metadata. Raw messages and generated content are never columns. */
@Entity
@Table(name = "assistant_conversation")
public class AssistantConversation {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false, length = 32)
    private AssistantConversationPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssistantConversationStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AssistantConversation() {
        // required by JPA
    }

    private AssistantConversation(UUID ownerAccountId, AssistantConversationPurpose purpose) {
        id = UUID.randomUUID();
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        status = AssistantConversationStatus.ACTIVE;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public static AssistantConversation create(UUID ownerAccountId, AssistantConversationPurpose purpose) {
        return new AssistantConversation(ownerAccountId, purpose);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public AssistantConversationPurpose getPurpose() {
        return purpose;
    }

    public AssistantConversationStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
