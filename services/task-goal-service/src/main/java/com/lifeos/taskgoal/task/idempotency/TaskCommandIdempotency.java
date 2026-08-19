package com.lifeos.taskgoal.task.idempotency;

import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskStatus;
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
 * One owner-scoped durable reservation for a Task create or lifecycle command.
 *
 * <p>A preallocated task identifier means a crash after the independently committed reservation
 * can be resumed without emitting a second Task. Completed rows retain an immutable response
 * snapshot, so a matching stale retry does not re-run a lifecycle transition.
 */
@Entity
@Table(
        name = "task_command_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_command_idempotency_scope_key",
                columnNames = {
                    "actor_account_id", "tenant_id", "operation", "target_scope", "idempotency_key_hash"
                }),
        indexes = @Index(name = "idx_task_command_idempotency_task", columnList = "task_id"))
public class TaskCommandIdempotency {

    @Id
    private UUID id;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TaskCommandOperation operation;

    @Column(name = "target_scope", nullable = false, length = 40, updatable = false)
    private String targetScope;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "expected_version", updatable = false)
    private Long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskCommandIdempotencyState state;

    @Column(name = "result_title", length = 255)
    private String resultTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", length = 16)
    private TaskStatus resultStatus;

    @Column(name = "result_version")
    private Long resultVersion;

    @Column(name = "result_created_at")
    private Instant resultCreatedAt;

    @Column(name = "result_updated_at")
    private Instant resultUpdatedAt;

    @Column(name = "result_completed_at")
    private Instant resultCompletedAt;

    @Column(name = "result_canceled_at")
    private Instant resultCanceledAt;

    @Column(name = "result_priority")
    private Integer resultPriority;

    @Column(name = "result_due_at")
    private Instant resultDueAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TaskCommandIdempotency() {
        // required by JPA
    }

    TaskCommandIdempotency(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            UUID taskId,
            String idempotencyKeyHash,
            String requestFingerprint,
            Long expectedVersion) {
        this.id = UUID.randomUUID();
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId must not be null");
        this.tenantId = requireNonBlank(tenantId, "tenantId");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.targetScope = requireNonBlank(targetScope, "targetScope");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        if (expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        this.expectedVersion = expectedVersion;
        this.state = TaskCommandIdempotencyState.PENDING;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getTaskId() {
        return taskId;
    }

    TaskCommandOperation getOperation() {
        return operation;
    }

    Long getExpectedVersion() {
        return expectedVersion;
    }

    boolean matchesRequestFingerprint(String candidate) {
        return requestFingerprint.equals(candidate);
    }

    boolean isCompleted() {
        return state == TaskCommandIdempotencyState.COMPLETED;
    }

    void complete(TaskLifecycleResult result) {
        if (isCompleted()) {
            return;
        }
        resultTitle = Objects.requireNonNull(result.title(), "result.title must not be null");
        resultStatus = Objects.requireNonNull(result.status(), "result.status must not be null");
        resultVersion = result.version();
        resultCreatedAt = Objects.requireNonNull(result.createdAt(), "result.createdAt must not be null");
        resultUpdatedAt = Objects.requireNonNull(result.updatedAt(), "result.updatedAt must not be null");
        resultCompletedAt = result.completedAt();
        resultCanceledAt = result.canceledAt();
        resultPriority = result.priority();
        resultDueAt = result.dueAt();
        completedAt = Instant.now();
        state = TaskCommandIdempotencyState.COMPLETED;
    }

    TaskLifecycleResult result() {
        if (!isCompleted()
                || resultTitle == null
                || resultStatus == null
                || resultVersion == null
                || resultCreatedAt == null
                || resultUpdatedAt == null) {
            throw new TaskIdempotencyUnavailableException();
        }
        return new TaskLifecycleResult(
                taskId,
                resultTitle,
                resultStatus,
                resultVersion,
                resultCreatedAt,
                resultUpdatedAt,
                resultCompletedAt,
                resultCanceledAt,
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
