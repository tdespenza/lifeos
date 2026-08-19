package com.lifeos.taskgoal.planning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineRepository extends JpaRepository<Routine, UUID> {
    List<Routine> findByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(UUID ownerAccountId, String tenantId);
    Optional<Routine> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);
}
