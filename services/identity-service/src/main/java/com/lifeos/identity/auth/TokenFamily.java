package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable refresh-token family authority. Only the active token digest and family deadlines are
 * stored; raw refresh credentials never cross the persistence boundary.
 */
@Entity
@Table(name = "refresh_token_family", indexes = {
        @Index(name = "ix_refresh_family_active_hash", columnList = "active_token_hash"),
        @Index(name = "ix_refresh_family_account", columnList = "account_id")
})
public class TokenFamily {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "active_token_hash", nullable = false, length = 64)
    private String activeTokenHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastUsedAt;

    @Column(nullable = false)
    private Instant refreshExpiresAt;

    @Column(nullable = false, updatable = false)
    private Instant familyExpiresAt;

    @Column(nullable = false)
    private Instant idleExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TokenFamilyStatus status;

    protected TokenFamily() {
        // required by JPA
    }

    public TokenFamily(
            UUID id,
            UUID accountId,
            UUID sessionId,
            String activeTokenHash,
            Instant createdAt,
            Instant refreshExpiresAt,
            Instant familyExpiresAt,
            Instant idleExpiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.sessionId = sessionId;
        this.activeTokenHash = activeTokenHash;
        this.createdAt = createdAt;
        this.lastUsedAt = createdAt;
        this.refreshExpiresAt = refreshExpiresAt;
        this.familyExpiresAt = familyExpiresAt;
        this.idleExpiresAt = idleExpiresAt;
        this.status = TokenFamilyStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getActiveTokenHash() {
        return activeTokenHash;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRefreshExpiresAt() {
        return refreshExpiresAt;
    }

    public Instant getFamilyExpiresAt() {
        return familyExpiresAt;
    }

    public Instant getIdleExpiresAt() {
        return idleExpiresAt;
    }

    public TokenFamilyStatus getStatus() {
        return status;
    }

    public void rotate(
            String successorHash,
            Instant usedAt,
            Instant successorRefreshExpiresAt,
            Instant successorIdleExpiresAt) {
        this.activeTokenHash = successorHash;
        this.lastUsedAt = usedAt;
        this.refreshExpiresAt = successorRefreshExpiresAt;
        this.idleExpiresAt = successorIdleExpiresAt;
    }

    public void revoke() {
        this.status = TokenFamilyStatus.REVOKED;
    }

    public void expire() {
        this.status = TokenFamilyStatus.EXPIRED;
    }
}
