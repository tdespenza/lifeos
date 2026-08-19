package com.lifeos.taskgoal.dependency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/** One durable lock target per personal graph, used only during dependency edge mutations. */
@Entity
@Table(
        name = "task_goal_dependency_guard",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_goal_dependency_guard_scope",
                columnNames = {"owner_account_id", "tenant_id"}))
public class TaskGoalDependencyGuard {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    protected TaskGoalDependencyGuard() {
        // required by JPA
    }

    TaskGoalDependencyGuard(UUID ownerAccountId, String tenantId) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        this.tenantId = tenantId;
    }

    UUID getId() {
        return id;
    }
}
