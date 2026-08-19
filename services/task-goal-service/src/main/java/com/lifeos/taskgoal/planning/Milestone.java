package com.lifeos.taskgoal.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Goal progress checkpoint with deterministic order and reopenable completion state. */
@Entity
@Table(name = "goal_milestone", indexes = @Index(name = "idx_goal_milestone_owner_goal", columnList = "owner_account_id, tenant_id, goal_id, position"))
public class Milestone {
    @Id
    private UUID id;
    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;
    @Column(nullable = false, length = 160)
    private String title;
    @Column(length = 2000)
    private String criteria;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false)
    private boolean completed;
    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;
    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected Milestone() {
    }

    public Milestone(UUID id, UUID goalId, String title, String criteria, int position, UUID ownerAccountId, String tenantId) {
        if (position < 0 || position > 10_000) {
            throw new IllegalArgumentException("position must be between 0 and 10000");
        }
        this.id = id;
        this.goalId = goalId;
        this.title = bounded(title, 160, "title");
        this.criteria = criteria == null ? null : bounded(criteria, 2000, "criteria");
        this.position = position;
        this.ownerAccountId = ownerAccountId;
        this.tenantId = tenantId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getGoalId() { return goalId; }
    public String getTitle() { return title; }
    public String getCriteria() { return criteria; }
    public int getPosition() { return position; }
    public boolean isCompleted() { return completed; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public long getVersion() { return version; }

    public void update(String title, String criteria, int position) {
        if (position < 0 || position > 10_000) {
            throw new IllegalArgumentException("position must be between 0 and 10000");
        }
        this.title = bounded(title, 160, "title");
        this.criteria = criteria == null ? null : bounded(criteria, 2000, "criteria");
        this.position = position;
        this.updatedAt = Instant.now();
    }

    public void toggleCompleted() {
        completed = !completed;
        updatedAt = Instant.now();
    }

    private static String bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value.trim();
    }
}
