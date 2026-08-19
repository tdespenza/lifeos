package com.lifeos.taskgoal.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner- and tenant-scoped actionable work item. Terminal tasks retain their audit history. */
@Entity
@Table(
        name = "task",
        indexes = @Index(name = "idx_task_owner_tenant", columnList = "owner_account_id, tenant_id"))
public class Task {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** Lower values are more urgent; the bounded range is 0 (critical) through 4 (low). */
    @Column(nullable = false)
    private int priority;

    @Column(name = "due_at")
    private Instant dueAt;

    /** Monotonic public representation version used by strong ETags and If-Match. */
    @Version
    @Column(nullable = false)
    private long version;

    protected Task() {
        // required by JPA
    }

    public Task(UUID id, String title, UUID ownerAccountId, String tenantId) {
        this(id, title, ownerAccountId, tenantId, 3, null);
    }

    public Task(UUID id, String title, UUID ownerAccountId, String tenantId, int priority, Instant dueAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = requireTitle(title);
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = requireNonBlank(tenantId, "tenantId");
        this.priority = requirePriority(priority);
        this.dueAt = dueAt;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.status = TaskStatus.ACTIVE;
        this.version = 0L;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public long getVersion() {
        return version;
    }

    public void rename(String newTitle) {
        updatePlanning(newTitle, priority, dueAt);
    }

    public void updatePlanning(String newTitle, int newPriority, Instant newDueAt) {
        requireActive("update");
        title = requireTitle(newTitle);
        priority = requirePriority(newPriority);
        dueAt = newDueAt;
        updatedAt = Instant.now();
    }

    public void complete() {
        requireActive("complete");
        Instant now = Instant.now();
        status = TaskStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    public void cancel() {
        requireActive("cancel");
        Instant now = Instant.now();
        status = TaskStatus.CANCELED;
        canceledAt = now;
        updatedAt = now;
    }

    private void requireActive(String operation) {
        if (status != TaskStatus.ACTIVE) {
            throw new TaskLifecycleTransitionException(operation);
        }
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("title must be non-blank and at most 255 characters");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePriority(int value) {
        if (value < 0 || value > 4) {
            throw new IllegalArgumentException("priority must be between 0 and 4");
        }
        return value;
    }
}
