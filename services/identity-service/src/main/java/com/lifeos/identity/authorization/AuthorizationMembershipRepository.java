package com.lifeos.identity.authorization;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Indexed persistence access for subject-and-tenant scoped role memberships. */
public interface AuthorizationMembershipRepository extends JpaRepository<AuthorizationMembership, UUID> {

    /**
     * Finds only current roles for one subject in one tenant.
     *
     * @param accountId subject account
     * @param tenantId resource tenant
     * @return active scoped memberships
     */
    List<AuthorizationMembership> findByAccountIdAndTenantIdAndActiveTrue(UUID accountId, String tenantId);
}
