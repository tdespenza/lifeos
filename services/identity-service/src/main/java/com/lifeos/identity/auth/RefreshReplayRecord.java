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

    @Column(name = "predecessor_token_hash", nullable = false, length = 64, updatable = false)
    private String predecessorTokenHash;

    /**
     * Preserve the established PostgreSQL large-object representation for replay envelopes.
     * Existing Hibernate-managed deployments can contain OID-backed values, so changing this to
     * {@code text} would require a separately reviewed data migration.
     */
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
            String predecessorTokenHash,
            Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.familyId = familyId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.predecessorTokenHash = predecessorTokenHash;
        this.expiresAt = expiresAt;
        this.state = RefreshReplayState.PENDING;
        this.retryCount = 0;
    }

    /**
     * Returns the owning token-family identifier.
     *
     * @return family identifier
     */
    public UUID getFamilyId() {
        return familyId;
    }

    /**
     * Returns the client idempotency key.
     *
     * @return idempotency key
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns the server-derived request fingerprint.
     *
     * @return request fingerprint
     */
    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    /**
     * Returns the digest of the predecessor consumed by this replay record.
     *
     * @return predecessor token digest
     */
    public String getPredecessorTokenHash() {
        return predecessorTokenHash;
    }

    /**
     * Returns the encrypted response envelope.
     *
     * @return encrypted response, or {@code null} while pending
     */
    public String getEncryptedResponse() {
        return encryptedResponse;
    }

    /**
     * Returns the durable replay state.
     *
     * @return replay state
     */
    public RefreshReplayState getState() {
        return state;
    }

    /**
     * Returns the number of matching retries already consumed.
     *
     * @return retry count
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Returns the replay envelope expiration instant.
     *
     * @return expiration instant
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Stores the encrypted response and makes the record retryable.
     *
     * @param encryptedResponse encrypted response envelope
     */
    public void commit(String encryptedResponse) {
        this.encryptedResponse = encryptedResponse;
        this.state = RefreshReplayState.COMMITTED;
    }

    /**
     * Consumes the one permitted matching retry.
     */
    public void consumeRetry() {
        this.retryCount++;
    }
}
