package com.lifeos.trustledger.anchor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Owner-scoped, digest-only anchor request for non-document artifacts such as Media summaries.
 * No transcript, prompt, title, or object-store reference is persisted here.
 */
@Entity
@Table(
        name = "trust_digest_anchor_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_trust_digest_anchor_scope_key",
                columnNames = {"owner_account_id", "tenant_id", "subject_type", "subject_id", "subject_version", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_trust_digest_anchor_owner", columnList = "owner_account_id,tenant_id,created_at"))
public class TrustDigestAnchorRequest {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "subject_type", nullable = false, length = 48, updatable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "subject_version", nullable = false, updatable = false)
    private long subjectVersion;

    @Column(name = "digest_sha256", nullable = false, length = 64, updatable = false)
    private String digestSha256;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrustDigestAnchorState state;

    @Column(name = "transaction_hash", length = 66)
    private String transactionHash;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrustDigestAnchorRequest() {
        // required by JPA
    }

    public TrustDigestAnchorRequest(
            UUID ownerAccountId,
            String tenantId,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String digestSha256,
            String idempotencyKeyHash,
            Instant now) {
        requestId = UUID.randomUUID();
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = boundedText(tenantId, "tenantId", 255);
        this.subjectType = boundedText(subjectType, "subjectType", 48);
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId must not be null");
        if (subjectVersion < 0) {
            throw new IllegalArgumentException("subjectVersion must not be negative");
        }
        this.subjectVersion = subjectVersion;
        this.digestSha256 = digest(digestSha256, "digestSha256");
        this.idempotencyKeyHash = digest(idempotencyKeyHash, "idempotencyKeyHash");
        state = TrustDigestAnchorState.PENDING_EXTERNAL_ANCHOR;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public UUID getRequestId() { return requestId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public long getSubjectVersion() { return subjectVersion; }
    public String getDigestSha256() { return digestSha256; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public TrustDigestAnchorState getState() { return state; }
    public String getTransactionHash() { return transactionHash; }
    public Long getBlockNumber() { return blockNumber; }
    public String getLastFailureCode() { return lastFailureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean matches(String digestSha256, long subjectVersion) {
        return this.subjectVersion == subjectVersion && this.digestSha256.equals(digestSha256);
    }

    public void claim(Instant now) {
        state = TrustDigestAnchorState.SUBMITTING;
        lastFailureCode = null;
        updatedAt = now;
    }

    public void markConfirmed(String transactionHash, long blockNumber, Instant now) {
        this.transactionHash = Objects.requireNonNull(transactionHash, "transactionHash must not be null");
        if (blockNumber < 0) {
            throw new IllegalArgumentException("blockNumber must not be negative");
        }
        this.blockNumber = blockNumber;
        state = TrustDigestAnchorState.CONFIRMED;
        updatedAt = now;
    }

    public void resetPending(String failureCode, Instant now) {
        if (failureCode == null || !failureCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("failureCode must be a bounded classification");
        }
        lastFailureCode = failureCode;
        state = TrustDigestAnchorState.PENDING_EXTERNAL_ANCHOR;
        updatedAt = now;
    }

    private static String boundedText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be bounded and nonblank");
        }
        return value;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
