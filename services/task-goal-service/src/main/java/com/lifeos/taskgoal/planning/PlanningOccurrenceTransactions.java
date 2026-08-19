package com.lifeos.taskgoal.planning;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates immutable occurrence uniqueness races from the enclosing idempotency transaction. */
@Service
public class PlanningOccurrenceTransactions {

    private final HabitOccurrenceRepository repository;

    public PlanningOccurrenceTransactions(HabitOccurrenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void saveHabitOccurrence(
            UUID id, UUID habitId, UUID ownerAccountId, String tenantId, LocalDate occurrenceDate) {
        repository.saveAndFlush(new HabitOccurrence(id, habitId, ownerAccountId, tenantId, occurrenceDate));
    }
}
