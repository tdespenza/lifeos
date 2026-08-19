package com.lifeos.taskgoal.goal.idempotency;

import com.lifeos.taskgoal.goal.GoalLifecycleResult;
import com.lifeos.taskgoal.goal.GoalStatus;
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
 * Durable actor-scoped reservation and immutable response snapshot for a lifecycle mutation.
 *
 * <p>Snapshots prevent a matching retry from accidentally returning a later mutation made with a
 * different idempotency key. Raw idempotency keys and original request payloads are never stored;
 * the response snapshot necessarily retains the goal title that was returned to the caller.
 */
@Entity
@Table(
        name = "goal_mutation_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_goal_mutation_idempotency_scope_key",
                columnNames = {
                    "actor_account_id", "tenant_id", "goal_id", "operation", "idempotency_key_hash"
                }),
        indexes = @Index(name = "idx_goal_mutation_idempotency_goal", columnList = "goal_id"))
public class GoalMutationIdempotency {

    @Id
    private UUID id;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private GoalMutationOperation operation;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "expected_version", nullable = false, updatable = false)
    private long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalMutationIdempotencyState state;

    @Column(name = "result_title", length = 255)
    private String resultTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", length = 16)
    private GoalStatus resultStatus;

    @Column(name = "result_version")
    private Long resultVersion;

    @Column(name = "result_created_at")
    private Instant resultCreatedAt;

    @Column(name = "result_updated_at")
    private Instant resultUpdatedAt;

    @Column(name = "result_completed_at")
    private Instant resultCompletedAt;

    @Column(name = "result_archived_at")
    private Instant resultArchivedAt;

    @Column(name = "result_priority")
    private Integer resultPriority;

    @Column(name = "result_due_at")
    private Instant resultDueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected GoalMutationIdempotency() {
        // required by JPA
    }

    GoalMutationIdempotency(
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String idempotencyKeyHash,
            String requestFingerprint,
            long expectedVersion) {
        this.id = UUID.randomUUID();
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId must not be null");
        this.tenantId = requireNonBlank(tenantId, "tenantId");
        this.goalId = Objects.requireNonNull(goalId, "goalId must not be null");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        this.expectedVersion = expectedVersion;
        this.state = GoalMutationIdempotencyState.PENDING;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getActorAccountId() {
        return actorAccountId;
    }

    String getTenantId() {
        return tenantId;
    }

    UUID getGoalId() {
        return goalId;
    }

    GoalMutationOperation getOperation() {
        return operation;
    }

    long getExpectedVersion() {
        return expectedVersion;
    }

    boolean matchesRequestFingerprint(String candidate) {
        return requestFingerprint.equals(candidate);
    }

    boolean isCompleted() {
        return state == GoalMutationIdempotencyState.COMPLETED;
    }

    void complete(GoalLifecycleResult result) {
        if (state == GoalMutationIdempotencyState.COMPLETED) {
            return;
        }
        resultTitle = Objects.requireNonNull(result.title(), "result.title must not be null");
        resultStatus = Objects.requireNonNull(result.status(), "result.status must not be null");
        resultVersion = result.version();
        resultCreatedAt = Objects.requireNonNull(result.createdAt(), "result.createdAt must not be null");
        resultUpdatedAt = Objects.requireNonNull(result.updatedAt(), "result.updatedAt must not be null");
        resultCompletedAt = result.completedAt();
        resultArchivedAt = result.archivedAt();
        resultPriority = result.priority();
        resultDueAt = result.dueAt();
        completedAt = Instant.now();
        state = GoalMutationIdempotencyState.COMPLETED;
    }

    GoalLifecycleResult result() {
        if (!isCompleted()
                || resultTitle == null
                || resultStatus == null
                || resultVersion == null
                || resultCreatedAt == null
                || resultUpdatedAt == null) {
            throw new GoalIdempotencyUnavailableException();
        }
        return new GoalLifecycleResult(
                goalId,
                resultTitle,
                resultStatus,
                resultVersion,
                resultCreatedAt,
                resultUpdatedAt,
                resultCompletedAt,
                resultArchivedAt,
                resultPriority == null ? 3 : resultPriority,
                resultDueAt);
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
