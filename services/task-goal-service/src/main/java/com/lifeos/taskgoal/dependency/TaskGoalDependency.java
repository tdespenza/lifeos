package com.lifeos.taskgoal.dependency;

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

/** A durable directed edge where predecessor must be executed before dependent. */
@Entity
@Table(
        name = "task_goal_dependency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_goal_dependency_edge",
                columnNames = {
                    "owner_account_id",
                    "tenant_id",
                    "predecessor_type",
                    "predecessor_id",
                    "dependent_type",
                    "dependent_id"
                }),
        indexes = {
            @Index(
                    name = "idx_task_goal_dependency_scope_predecessor",
                    columnList = "owner_account_id, tenant_id, predecessor_type, predecessor_id"),
            @Index(
                    name = "idx_task_goal_dependency_scope_dependent",
                    columnList = "owner_account_id, tenant_id, dependent_type, dependent_id")
        })
public class TaskGoalDependency {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "predecessor_type", nullable = false, length = 16, updatable = false)
    private DependencyNodeType predecessorType;

    @Column(name = "predecessor_id", nullable = false, updatable = false)
    private UUID predecessorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependent_type", nullable = false, length = 16, updatable = false)
    private DependencyNodeType dependentType;

    @Column(name = "dependent_id", nullable = false, updatable = false)
    private UUID dependentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskGoalDependency() {
        // required by JPA
    }

    public TaskGoalDependency(
            UUID ownerAccountId,
            String tenantId,
            PersistedDependencyNode predecessor,
            PersistedDependencyNode dependent) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        this.tenantId = tenantId;
        predecessor = Objects.requireNonNull(predecessor, "predecessor must not be null");
        dependent = Objects.requireNonNull(dependent, "dependent must not be null");
        if (predecessor.equals(dependent)) {
            throw new SelfDependencyException();
        }
        this.predecessorType = predecessor.type();
        this.predecessorId = predecessor.id();
        this.dependentType = dependent.type();
        this.dependentId = dependent.id();
        this.createdAt = Instant.now();
    }

    public PersistedDependencyNode predecessor() {
        return new PersistedDependencyNode(predecessorType, predecessorId);
    }

    public PersistedDependencyNode dependent() {
        return new PersistedDependencyNode(dependentType, dependentId);
    }
}
