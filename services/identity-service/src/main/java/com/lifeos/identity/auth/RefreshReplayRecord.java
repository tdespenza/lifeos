package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable one-retry record. The response envelope is encrypted before persistence and expires
 * quickly, so an ambiguous network result can be retried without minting a second successor.
 */
@Entity
@Table(name = "refresh_replay_record",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refresh_replay_family_key", columnNames = {"family_id", "idempotency_key"}),
        indexes = @Index(name = "ix_refresh_replay_expires", columnList = "expires_at"))
public class RefreshReplayRecord {

    @Id
    private UUID id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Lob
    @Column(name = "encrypted_response")
    private String encryptedResponse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RefreshReplayState state;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    protected RefreshReplayRecord() {
        // required by JPA
    }

    public RefreshReplayRecord(
            UUID familyId,
            String idempotencyKey,
            String requestFingerprint,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.familyId = familyId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt;
        this.state = RefreshReplayState.PENDING;
        this.retryCount = 0;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getEncryptedResponse() {
        return encryptedResponse;
    }

    public RefreshReplayState getState() {
        return state;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void commit(String encryptedResponse) {
        this.encryptedResponse = encryptedResponse;
        this.state = RefreshReplayState.COMMITTED;
    }

    public void consumeRetry() {
        this.retryCount++;
    }
}
