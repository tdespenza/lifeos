package com.lifeos.taskgoal.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitOccurrenceRepository extends JpaRepository<HabitOccurrence, UUID> {
    Optional<HabitOccurrence> findByHabitIdAndOwnerAccountIdAndTenantIdAndOccurrenceDate(
            UUID habitId, UUID ownerAccountId, String tenantId, LocalDate occurrenceDate);
    List<HabitOccurrence> findByHabitIdAndOwnerAccountIdAndTenantIdAndOccurrenceDateBetweenOrderByOccurrenceDateAsc(
            UUID habitId, UUID ownerAccountId, String tenantId, LocalDate from, LocalDate to);
}
