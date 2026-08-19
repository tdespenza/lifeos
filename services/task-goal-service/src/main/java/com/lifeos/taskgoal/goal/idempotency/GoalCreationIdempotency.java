package com.lifeos.taskgoal.goal.idempotency;

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
 * Durable reservation for one account- and tenant-scoped goal create request.
 *
 * <p>The client key and request body are represented only by SHA-256 digests. The row reserves a
 * stable goal identifier before the goal transaction starts, which makes a process crash between
 * those transactions recoverable without generating a second goal.
 */
@Entity
@Table(
        name = "goal_create_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_goal_create_idempotency_scope_key",
                columnNames = {"owner_account_id", "tenant_id", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_goal_create_idempotency_goal", columnList = "goal_id"))
public class GoalCreationIdempotency {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalCreationIdempotencyState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected GoalCreationIdempotency() {
        // required by JPA
    }

    public GoalCreationIdempotency(
            UUID ownerAccountId,
            String tenantId,
            String idempotencyKeyHash,
            String requestFingerprint,
            UUID goalId) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = requireNonBlank(tenantId, "tenantId");
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        this.goalId = Objects.requireNonNull(goalId, "goalId must not be null");
        this.state = GoalCreationIdempotencyState.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public GoalCreationIdempotencyState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Checks whether a retry uses the exact canonical request represented by this reservation.
     *
     * @param candidateFingerprint server-derived request fingerprint
     * @return whether this is a replay of the original payload
     */
    public boolean matchesRequestFingerprint(String candidateFingerprint) {
        return requestFingerprint.equals(candidateFingerprint);
    }

    public boolean isCompleted() {
        return state == GoalCreationIdempotencyState.COMPLETED;
    }

    /** Marks the reservation replayable after its goal row is durably present in the same transaction. */
    public void complete() {
        if (state == GoalCreationIdempotencyState.PENDING) {
            state = GoalCreationIdempotencyState.COMPLETED;
            completedAt = Instant.now();
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
