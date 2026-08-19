package com.lifeos.taskgoal.dependency;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskDependencyGraphAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.TaskCommandAudit;
import com.lifeos.taskgoal.task.TaskRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** Public dependency use cases with local scope checks before a graph guard is acquired. */
@Service
public class PersistedDependencyService {

    private final TaskAccessService accessService;
    private final TaskRepository taskRepository;
    private final GoalRepository goalRepository;
    private final TaskGoalDependencyGuardService guardService;
    private final PersistedDependencyGraphTransactions transactions;
    private final TaskCommandAudit audit;

    public PersistedDependencyService(
            TaskAccessService accessService,
            TaskRepository taskRepository,
            GoalRepository goalRepository,
            TaskGoalDependencyGuardService guardService,
            PersistedDependencyGraphTransactions transactions,
            TaskCommandAudit audit) {
        this.accessService = accessService;
        this.taskRepository = taskRepository;
        this.goalRepository = goalRepository;
        this.guardService = guardService;
        this.transactions = transactions;
        this.audit = audit;
    }

    /** Adds one directed predecessor -> dependent edge; an existing identical edge is a no-op. */
    public boolean add(TaskSubject subject, PersistedDependencyNode predecessor, PersistedDependencyNode dependent) {
        authorize(subject, TaskAuthorizationActions.DEPENDENCY_MANAGE);
        requireAccessible(subject, predecessor);
        requireAccessible(subject, dependent);
        try {
            boolean created = transactions.add(
                    guardService.guardId(subject.accountId(), subject.tenantId()),
                    subject.accountId(),
                    subject.tenantId(),
                    predecessor,
                    dependent);
            audit.success("dependency-manage");
            return created;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            audit.rejected("dependency-manage");
            throw new DependencyPersistenceUnavailableException();
        } catch (RuntimeException exception) {
            audit.rejected("dependency-manage");
            throw exception;
        }
    }

    /** Removes one directed edge; a missing identical edge is deliberately a no-op. */
    public boolean remove(TaskSubject subject, PersistedDependencyNode predecessor, PersistedDependencyNode dependent) {
        authorize(subject, TaskAuthorizationActions.DEPENDENCY_MANAGE);
        requireAccessible(subject, predecessor);
        requireAccessible(subject, dependent);
        try {
            boolean removed = transactions.remove(
                    guardService.guardId(subject.accountId(), subject.tenantId()),
                    subject.accountId(),
                    subject.tenantId(),
                    predecessor,
                    dependent);
            audit.success("dependency-manage");
            return removed;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            audit.rejected("dependency-manage");
            throw new DependencyPersistenceUnavailableException();
        } catch (RuntimeException exception) {
            audit.rejected("dependency-manage");
            throw exception;
        }
    }

    /** Returns all caller-owned persisted nodes in deterministic dependency-respecting order. */
    public List<PersistedDependencyNode> order(TaskSubject subject) {
        authorize(subject, TaskAuthorizationActions.DEPENDENCY_ORDER);
        try {
            List<PersistedDependencyNode> order = transactions.order(subject.accountId(), subject.tenantId());
            audit.success("dependency-order");
            return order;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            audit.rejected("dependency-order");
            throw new DependencyPersistenceUnavailableException();
        } catch (RuntimeException exception) {
            audit.rejected("dependency-order");
            throw exception;
        }
    }

    private void authorize(TaskSubject subject, String action) {
        accessService.authorize(
                subject, action, TaskDependencyGraphAuthorizationResource.forCollection(subject.tenantId()));
    }

    private void requireAccessible(TaskSubject subject, PersistedDependencyNode node) {
        boolean present = switch (node.type()) {
            case TASK -> taskRepository.findByIdAndOwnerAccountIdAndTenantId(
                    node.id(), subject.accountId(), subject.tenantId()).isPresent();
            case GOAL -> goalRepository.findByIdAndOwnerAccountIdAndTenantId(
                    node.id(), subject.accountId(), subject.tenantId()).isPresent();
        };
        if (!present) {
            // Missing and cross-user identifiers intentionally share one generic response.
            throw new TaskAuthorizationDenied();
        }
    }
}
