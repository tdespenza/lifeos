package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Replay evidence retained for the whole token-family lifetime.
 */
@Entity
@Table(name = "consumed_refresh_token",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_consumed_refresh_token_hash", columnNames = "token_hash"),
        indexes = @Index(name = "ix_consumed_refresh_family", columnList = "family_id"))
public class ConsumedRefreshToken {

    @Id
    private UUID id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    protected ConsumedRefreshToken() {
        // required by JPA
    }

    /**
     * Creates replay evidence for one consumed predecessor.
     *
     * @param familyId owning token family
     * @param tokenHash predecessor digest
     * @param consumedAt consumption instant
     */
    public ConsumedRefreshToken(UUID familyId, String tokenHash, Instant consumedAt) {
        this.id = UUID.randomUUID();
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.consumedAt = consumedAt;
    }

    /**
     * Returns the owning family identifier.
     *
     * @return family identifier
     */
    public UUID getFamilyId() {
        return familyId;
    }

    /**
     * Returns the consumed predecessor digest.
     *
     * @return token digest
     */
    public String getTokenHash() {
        return tokenHash;
    }
}
