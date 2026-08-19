package com.lifeos.trustledger.certificate;

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
 * Privacy-minimized completed-goal certificate. It stores only immutable completion facts and the
 * derived digest; title, notes, tasks, and other goal content never enter this table or the chain.
 */
@Entity
@Table(
        name = "trust_goal_certificate",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_trust_goal_certificate_scope_key",
                columnNames = {"owner_account_id", "tenant_id", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_trust_goal_certificate_owner", columnList = "owner_account_id, tenant_id"))
public class TrustGoalCertificate {

    @Id
    @Column(name = "certificate_id", nullable = false, updatable = false)
    private UUID certificateId;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(name = "goal_version", nullable = false, updatable = false)
    private long goalVersion;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "achievement_digest_sha256", nullable = false, updatable = false, length = 64)
    private String achievementDigestSha256;

    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrustGoalCertificateState state;

    @Column(name = "transaction_hash", length = 66)
    private String transactionHash;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrustGoalCertificate() {
        // required by JPA
    }

    public TrustGoalCertificate(
            UUID certificateId,
            UUID goalId,
            UUID ownerAccountId,
            String tenantId,
            long goalVersion,
            Instant completedAt,
            String achievementDigestSha256,
            String idempotencyKeyHash,
            Instant now) {
        this.certificateId = Objects.requireNonNull(certificateId, "certificateId must not be null");
        this.goalId = Objects.requireNonNull(goalId, "goalId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("tenantId must be bounded and nonblank");
        }
        this.tenantId = tenantId;
        if (goalVersion < 0) {
            throw new IllegalArgumentException("goalVersion must not be negative");
        }
        this.goalVersion = goalVersion;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (achievementDigestSha256 == null || !achievementDigestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("achievementDigestSha256 must be a SHA-256 digest");
        }
        this.achievementDigestSha256 = achievementDigestSha256;
        if (idempotencyKeyHash == null || !idempotencyKeyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("idempotencyKeyHash must be a SHA-256 digest");
        }
        this.idempotencyKeyHash = idempotencyKeyHash;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
        state = TrustGoalCertificateState.PENDING_EXTERNAL_ANCHOR;
    }

    public UUID getCertificateId() { return certificateId; }
    public UUID getGoalId() { return goalId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public long getGoalVersion() { return goalVersion; }
    public Instant getCompletedAt() { return completedAt; }
    public String getAchievementDigestSha256() { return achievementDigestSha256; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public TrustGoalCertificateState getState() { return state; }
    public String getTransactionHash() { return transactionHash; }
    public Long getBlockNumber() { return blockNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void confirm(String transactionHash, long blockNumber, Instant now) {
        if (transactionHash == null || transactionHash.isBlank() || blockNumber < 0) {
            throw new IllegalArgumentException("anchor receipt is invalid");
        }
        this.transactionHash = transactionHash;
        this.blockNumber = blockNumber;
        state = TrustGoalCertificateState.CONFIRMED;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public boolean claimAnchor(Instant now) {
        if (state != TrustGoalCertificateState.PENDING_EXTERNAL_ANCHOR) {
            return false;
        }
        state = TrustGoalCertificateState.SUBMITTING;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
        return true;
    }

    public void resetPending(Instant now) {
        if (state == TrustGoalCertificateState.SUBMITTING) {
            state = TrustGoalCertificateState.PENDING_EXTERNAL_ANCHOR;
            updatedAt = Objects.requireNonNull(now, "now must not be null");
        }
    }
}
