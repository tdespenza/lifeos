package com.lifeos.taskgoal.dependency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskCommandAudit;
import com.lifeos.taskgoal.task.TaskRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

/** Ensures database lock failures stay retryable rather than becoming an ambiguous HTTP 500. */
class PersistedDependencyServiceTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void mapsGraphMutationLockFailureToTheBoundedPersistenceOutcome() {
        TaskAccessService accessService = mock(TaskAccessService.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        GoalRepository goalRepository = mock(GoalRepository.class);
        TaskGoalDependencyGuardService guardService = mock(TaskGoalDependencyGuardService.class);
        PersistedDependencyGraphTransactions transactions = mock(PersistedDependencyGraphTransactions.class);
        TaskCommandAudit audit = mock(TaskCommandAudit.class);
        PersistedDependencyService service = new PersistedDependencyService(
                accessService, taskRepository, goalRepository, guardService, transactions, audit);
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        Task predecessor = new Task(UUID.randomUUID(), "First", subject.accountId(), subject.tenantId());
        Task dependent = new Task(UUID.randomUUID(), "Second", subject.accountId(), subject.tenantId());
        PersistedDependencyNode predecessorNode = new PersistedDependencyNode(DependencyNodeType.TASK, predecessor.getId());
        PersistedDependencyNode dependentNode = new PersistedDependencyNode(DependencyNodeType.TASK, dependent.getId());

        when(taskRepository.findByIdAndOwnerAccountIdAndTenantId(
                        predecessor.getId(), subject.accountId(), subject.tenantId()))
                .thenReturn(Optional.of(predecessor));
        when(taskRepository.findByIdAndOwnerAccountIdAndTenantId(
                        dependent.getId(), subject.accountId(), subject.tenantId()))
                .thenReturn(Optional.of(dependent));
        when(guardService.guardId(subject.accountId(), subject.tenantId())).thenReturn(UUID.randomUUID());
        when(transactions.add(any(), eq(subject.accountId()), eq(subject.tenantId()), eq(predecessorNode), eq(dependentNode)))
                .thenThrow(new CannotAcquireLockException("test lock timeout"));

        assertThatThrownBy(() -> service.add(subject, predecessorNode, dependentNode))
                .isInstanceOf(DependencyPersistenceUnavailableException.class)
                .hasNoCause();

        verify(audit).rejected("dependency-manage");
    }
}
