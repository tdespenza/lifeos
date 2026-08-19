package com.lifeos.taskgoal.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineOccurrenceRepository extends JpaRepository<RoutineOccurrence, UUID> {
    List<RoutineOccurrence> findByRoutineIdAndOwnerAccountIdAndTenantIdAndOccurrenceDateBetweenOrderByOccurrenceDateAsc(
            UUID routineId, UUID ownerAccountId, String tenantId, LocalDate from, LocalDate to);
    boolean existsByRoutineIdAndOwnerAccountIdAndTenantIdAndOccurrenceDate(
            UUID routineId, UUID ownerAccountId, String tenantId, LocalDate occurrenceDate);
}
