package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable refresh-token family authority. Only the active token digest and family deadlines are
 * stored; raw refresh credentials never cross the persistence boundary.
 */
@Entity
@Table(name = "refresh_token_family",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refresh_family_active_hash", columnNames = "active_token_hash"),
        indexes = @Index(name = "ix_refresh_family_account", columnList = "account_id"))
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

    /**
     * Returns the token-family identifier.
     *
     * @return family identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning account identifier.
     *
     * @return account identifier
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Returns the durable access-token session identifier.
     *
     * @return session identifier
     */
    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns the digest of the currently active refresh token.
     *
     * @return active token digest
     */
    public String getActiveTokenHash() {
        return activeTokenHash;
    }

    /**
     * Returns the last successful rotation instant.
     *
     * @return last-use instant
     */
    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    /**
     * Returns the current refresh-token expiration instant.
     *
     * @return refresh expiration instant
     */
    public Instant getRefreshExpiresAt() {
        return refreshExpiresAt;
    }

    /**
     * Returns the absolute family expiration instant.
     *
     * @return family expiration instant
     */
    public Instant getFamilyExpiresAt() {
        return familyExpiresAt;
    }

    /**
     * Returns the idle expiration instant.
     *
     * @return idle expiration instant
     */
    public Instant getIdleExpiresAt() {
        return idleExpiresAt;
    }

    /**
     * Returns the terminal or active family status.
     *
     * @return family status
     */
    public TokenFamilyStatus getStatus() {
        return status;
    }

    /**
     * Replaces the active credential after a successful atomic rotation.
     *
     * @param successorHash successor token digest
     * @param usedAt rotation instant
     * @param successorRefreshExpiresAt successor credential expiration
     * @param successorIdleExpiresAt successor idle expiration
     */
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

    /**
     * Permanently revokes this family.
     */
    public void revoke() {
        this.status = TokenFamilyStatus.REVOKED;
    }

    /**
     * Marks an active family expired without overwriting a prior revocation.
     */
    public void expire() {
        if (this.status == TokenFamilyStatus.ACTIVE) {
            this.status = TokenFamilyStatus.EXPIRED;
        }
    }
}
