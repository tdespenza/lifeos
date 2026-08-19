package com.lifeos.taskgoal.planning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilestoneRepository extends JpaRepository<Milestone, UUID> {
    List<Milestone> findByGoalIdAndOwnerAccountIdAndTenantIdOrderByPositionAscIdAsc(
            UUID goalId, UUID ownerAccountId, String tenantId);
    Optional<Milestone> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);
}
