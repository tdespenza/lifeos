package com.lifeos.taskgoal.dependency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Independent guard reservation transaction, so a duplicate-create race does not poison a graph write. */
@Service
public class TaskGoalDependencyGuardTransactions {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final TaskGoalDependencyGuardRepository repository;

    public TaskGoalDependencyGuardTransactions(TaskGoalDependencyGuardRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<TaskGoalDependencyGuard> find(UUID ownerAccountId, String tenantId) {
        return repository.findByOwnerAccountIdAndTenantId(ownerAccountId, tenantId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public TaskGoalDependencyGuard reserve(UUID ownerAccountId, String tenantId) {
        return repository.saveAndFlush(new TaskGoalDependencyGuard(ownerAccountId, tenantId));
    }
}
