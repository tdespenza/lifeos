package com.lifeos.identity.authorization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable explicit role membership scoped to a tenant.
 *
 * <p>A subject's personal-tenant MEMBER role is implicit and is therefore not stored in this
 * table. Explicit memberships allow controlled exceptions such as a tenant administrator without
 * expanding token claims or trusting stale client-side roles.
 */
@Entity
@Table(
        name = "authorization_membership",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_authorization_membership_account_tenant_role",
                columnNames = {"account_id", "tenant_id", "role"}))
public class AuthorizationMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private AuthorizationRole role;

    @Column(nullable = false)
    private boolean active;

    /** Creates an empty entity for JPA materialization. */
    protected AuthorizationMembership() {
        // required by JPA
    }

    /**
     * Creates an active explicit role membership.
     *
     * @param accountId account that receives the role
     * @param tenantId tenant in which the role applies
     * @param role granted role
     */
    public AuthorizationMembership(UUID accountId, String tenantId, AuthorizationRole role) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 128) {
            throw new IllegalArgumentException("tenantId must be between 1 and 128 characters");
        }
        this.tenantId = tenantId;
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.active = true;
    }

    /** @return role-owning account */
    public UUID getAccountId() {
        return accountId;
    }

    /** @return scoped tenant */
    public String getTenantId() {
        return tenantId;
    }

    /** @return explicitly granted role */
    public AuthorizationRole getRole() {
        return role;
    }

    /** @return whether this membership currently contributes to a decision */
    public boolean isActive() {
        return active;
    }

    /** Deactivates this membership without deleting audit-relevant history. */
    public void deactivate() {
        active = false;
    }
}
