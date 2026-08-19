package com.lifeos.taskgoal.planning;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanningCommandIdempotencyRepository extends JpaRepository<PlanningCommandIdempotency, UUID> {
    Optional<PlanningCommandIdempotency> findByOwnerAccountIdAndTenantIdAndOperationAndKeyHash(
            UUID ownerAccountId, String tenantId, String operation, String keyHash);
}
