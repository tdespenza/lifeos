package com.lifeos.taskgoal.goal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.createdAt = Instant.now();
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

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }
}
