package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Replay evidence retained for the whole token-family lifetime.
 */
@Entity
@Table(name = "consumed_refresh_token", indexes = {
        @Index(name = "ix_consumed_refresh_family", columnList = "family_id"),
        @Index(name = "ix_consumed_refresh_hash", columnList = "token_hash", unique = true)
})
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

    public ConsumedRefreshToken(UUID familyId, String tokenHash, Instant consumedAt) {
        this.id = UUID.randomUUID();
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.consumedAt = consumedAt;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }
}
