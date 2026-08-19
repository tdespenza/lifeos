package com.lifeos.taskgoal.dependency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** Resolves one durable graph lock target without holding it across authentication or request parsing. */
@Service
public class TaskGoalDependencyGuardService {

    private final TaskGoalDependencyGuardTransactions transactions;

    public TaskGoalDependencyGuardService(TaskGoalDependencyGuardTransactions transactions) {
        this.transactions = transactions;
    }

    public UUID guardId(UUID ownerAccountId, String tenantId) {
        Optional<TaskGoalDependencyGuard> existing = find(ownerAccountId, tenantId);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        try {
            return transactions.reserve(ownerAccountId, tenantId).getId();
        } catch (DataIntegrityViolationException exception) {
            return find(ownerAccountId, tenantId)
                    .map(TaskGoalDependencyGuard::getId)
                    .orElseThrow(DependencyPersistenceUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DependencyPersistenceUnavailableException();
        }
    }

    private Optional<TaskGoalDependencyGuard> find(UUID ownerAccountId, String tenantId) {
        try {
            return transactions.find(ownerAccountId, tenantId);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DependencyPersistenceUnavailableException();
        }
    }
}
