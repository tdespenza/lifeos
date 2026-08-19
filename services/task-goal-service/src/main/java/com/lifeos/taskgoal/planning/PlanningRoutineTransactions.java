package com.lifeos.taskgoal.planning;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates routine materialization uniqueness races from the command transaction. */
@Service
public class PlanningRoutineTransactions {

    private final RoutineOccurrenceRepository repository;

    public PlanningRoutineTransactions(RoutineOccurrenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void saveOccurrence(UUID id, UUID routineId, UUID ownerAccountId, String tenantId, LocalDate date) {
        repository.saveAndFlush(new RoutineOccurrence(id, routineId, ownerAccountId, tenantId, date));
    }
}
