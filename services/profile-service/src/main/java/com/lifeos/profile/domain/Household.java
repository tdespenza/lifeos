package com.lifeos.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A family or household scope owned by an account and administered through explicit memberships. */
@Entity
@Table(name = "household", indexes = @Index(name = "idx_household_owner_tenant", columnList = "owner_account_id, tenant_id"))
public class Household {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Household() {
        // required by JPA
    }

    public Household(UUID id, UUID ownerAccountId, String tenantId, String name) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("tenantId must be a bounded non-blank value");
        }
        if (!tenantId.equals(ownerAccountId.toString())) {
            throw new IllegalArgumentException("household tenant must be its owner's personal tenant");
        }
        this.tenantId = tenantId;
        rename(name);
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        version = 0L;
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

    public String getName() {
        return name;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void rename(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new IllegalArgumentException("household name must be a bounded non-blank value");
        }
        this.name = name.trim();
        updatedAt = Instant.now();
    }

    /** Forces a representation version bump when a membership or scoped permission changes. */
    public void touch() {
        updatedAt = Instant.now();
    }
}
