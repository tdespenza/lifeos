package com.lifeos.taskgoal.goal;

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

@Entity
@Table(
        name = "goal",
        indexes = @Index(name = "idx_goal_owner_tenant", columnList = "owner_account_id, tenant_id"))
public class Goal {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Lower values are more urgent; the bounded range is 0 (critical) through 4 (low). */
    @Column(nullable = false)
    private int priority;

    @Column(name = "due_at")
    private Instant dueAt;

    /**
     * Monotonic representation version exposed through {@code ETag} / {@code If-Match}.
     *
     * <p>Lifecycle writes also take a short pessimistic lock, so separately idempotent callers
     * observe a deterministic stale-version failure instead of two accepted transitions. The
     * version protects the same invariant against future write paths that do not share that lock.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /*
     * The columns deliberately remain nullable at the database layer during a rolling upgrade.
     * Existing rows that predate owner scoping must fail closed in the service and are never
     * returned by owner/tenant queries. New Goal instances always require both values.
     */
    @Column(name = "owner_account_id", updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", updatable = false)
    private String tenantId;

    protected Goal() {
        // required by JPA
    }

    public Goal(UUID id, String title, UUID ownerAccountId, String tenantId) {
        this(id, title, ownerAccountId, tenantId, 3, null);
    }

    public Goal(UUID id, String title, UUID ownerAccountId, String tenantId, int priority, Instant dueAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.priority = requirePriority(priority);
        this.dueAt = dueAt;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.status = GoalStatus.ACTIVE;
        this.version = 0L;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    /** Renames an active goal. Completed and archived records are immutable. */
    public void rename(String newTitle) {
        updatePlanning(newTitle, priority, dueAt);
    }

    public void updatePlanning(String newTitle, int newPriority, Instant newDueAt) {
        requireActive("rename");
        title = Objects.requireNonNull(newTitle, "newTitle must not be null");
        priority = requirePriority(newPriority);
        dueAt = newDueAt;
        updatedAt = Instant.now();
    }

    /** Moves an active goal into its completed state exactly once. */
    public void complete() {
        requireActive("complete");
        Instant now = Instant.now();
        status = GoalStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    /** Archives either an active or completed goal. Archived records are terminal. */
    public void archive() {
        if (status == GoalStatus.ARCHIVED) {
            throw new GoalLifecycleTransitionException("archive");
        }
        Instant now = Instant.now();
        status = GoalStatus.ARCHIVED;
        archivedAt = now;
        updatedAt = now;
    }

    private void requireActive(String operation) {
        if (status != GoalStatus.ACTIVE) {
            throw new GoalLifecycleTransitionException(operation);
        }
    }

    private static int requirePriority(int value) {
        if (value < 0 || value > 4) {
            throw new IllegalArgumentException("priority must be between 0 and 4");
        }
        return value;
    }
}
