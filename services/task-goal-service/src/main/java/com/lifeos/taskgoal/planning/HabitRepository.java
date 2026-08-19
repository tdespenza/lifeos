package com.lifeos.taskgoal.planning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitRepository extends JpaRepository<Habit, UUID> {
    List<Habit> findByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(UUID ownerAccountId, String tenantId);
    Optional<Habit> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);
}
