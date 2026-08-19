package com.lifeos.taskgoal.task;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.task.idempotency.TaskCommandIdempotencyService;
import com.lifeos.taskgoal.task.idempotency.TaskCommandOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Task lifecycle application service; public ownership facts are never accepted from a caller. */
@Service
public class TaskService {

    private final TaskRepository repository;
    private final TaskAccessService accessService;
    private final TaskCommandIdempotencyService idempotencyService;
    private final TaskCommandAudit audit;

    public TaskService(
            TaskRepository repository,
            TaskAccessService accessService,
            TaskCommandIdempotencyService idempotencyService,
            TaskCommandAudit audit) {
        this.repository = repository;
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.audit = audit;
    }

    public TaskLifecycleResult create(TaskSubject subject, String title, String idempotencyKey) {
        return create(subject, title, 3, null, idempotencyKey);
    }

    public TaskLifecycleResult create(
            TaskSubject subject, String title, Integer priority, java.time.Instant dueAt, String idempotencyKey) {
        UUID taskId = UUID.randomUUID();
        accessService.authorize(
                subject,
                TaskAuthorizationActions.CREATE,
                TaskAuthorizationResource.forNewTask(taskId, subject.accountId(), subject.tenantId()));
        try {
            TaskLifecycleResult result = idempotencyService.createOrReplay(
                    subject.accountId(), subject.tenantId(), taskId, title, priority == null ? 3 : priority, dueAt, idempotencyKey);
            audit.success("create");
            return result;
        } catch (RuntimeException exception) {
            audit.rejected("create");
            throw exception;
        }
    }

    public List<Task> listAll(TaskSubject subject) {
        accessService.authorize(
                subject, TaskAuthorizationActions.LIST, TaskAuthorizationResource.forCollection(subject.tenantId()));
        return repository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(
                subject.accountId(), subject.tenantId());
    }

    public Task get(TaskSubject subject, UUID taskId) {
        return loadAuthorizedTask(subject, taskId, TaskAuthorizationActions.READ);
    }

    public TaskLifecycleResult update(
            TaskSubject subject, UUID taskId, long expectedVersion, String title, String idempotencyKey) {
        Task task = loadAuthorizedTask(subject, taskId, TaskAuthorizationActions.UPDATE);
        return mutate(
                subject,
                task,
                expectedVersion,
                title,
                idempotencyKey,
                TaskAuthorizationActions.UPDATE,
                TaskCommandOperation.UPDATE,
                "update",
                null,
                null);
    }

    public TaskLifecycleResult update(
            TaskSubject subject,
            UUID taskId,
            long expectedVersion,
            String title,
            Integer priority,
            java.time.Instant dueAt,
            String idempotencyKey) {
        Task task = loadAuthorizedTask(subject, taskId, TaskAuthorizationActions.UPDATE);
        return mutate(
                subject,
                task,
                expectedVersion,
                title,
                idempotencyKey,
                TaskAuthorizationActions.UPDATE,
                TaskCommandOperation.UPDATE,
                "update",
                priority == null ? task.getPriority() : priority,
                dueAt == null ? task.getDueAt() : dueAt);
    }

    public TaskLifecycleResult complete(TaskSubject subject, UUID taskId, long expectedVersion, String idempotencyKey) {
        return mutate(subject, loadAuthorizedTask(subject, taskId, TaskAuthorizationActions.COMPLETE), expectedVersion, null, idempotencyKey, TaskAuthorizationActions.COMPLETE,
                TaskCommandOperation.COMPLETE, "complete", null, null);
    }

    public TaskLifecycleResult cancel(TaskSubject subject, UUID taskId, long expectedVersion, String idempotencyKey) {
        return mutate(subject, loadAuthorizedTask(subject, taskId, TaskAuthorizationActions.CANCEL), expectedVersion, null, idempotencyKey, TaskAuthorizationActions.CANCEL,
                TaskCommandOperation.CANCEL, "cancel", null, null);
    }

    private TaskLifecycleResult mutate(
            TaskSubject subject,
            Task task,
            long expectedVersion,
            String title,
            String idempotencyKey,
            String authorizationAction,
            TaskCommandOperation operation,
            String auditOperation,
            Integer priority,
            java.time.Instant dueAt) {
        try {
            TaskLifecycleResult result = priority == null
                    ? idempotencyService.mutateOrReplay(
                            subject.accountId(), subject.tenantId(), task, operation, expectedVersion, title, idempotencyKey)
                    : idempotencyService.mutateOrReplay(
                            subject.accountId(), subject.tenantId(), task, operation, expectedVersion, title, priority, dueAt, idempotencyKey);
            audit.success(auditOperation);
            return result;
        } catch (RuntimeException exception) {
            audit.rejected(auditOperation);
            throw exception;
        }
    }

    /**
     * Authenticates the identity decision against exact locally loaded facts, then applies a local
     * personal-scope check. This second check ensures a future tenant-admin policy cannot turn a
     * guessed cross-user Task ID into a disclosure path in this personal-task API.
     */
    private Task loadAuthorizedTask(TaskSubject subject, UUID taskId, String action) {
        Optional<Task> candidate = repository.findById(taskId);
        Task trustedTask = candidate.filter(TaskService::hasTrustedOwnership).orElse(null);
        TaskAuthorizationResource resource = trustedTask == null
                ? TaskAuthorizationResource.forMissingTask(taskId, subject.tenantId())
                : TaskAuthorizationResource.fromTask(trustedTask);
        accessService.authorize(subject, action, resource);
        if (trustedTask == null
                || !subject.accountId().equals(trustedTask.getOwnerAccountId())
                || !subject.tenantId().equals(trustedTask.getTenantId())) {
            throw new TaskAuthorizationDenied();
        }
        return trustedTask;
    }

    private static boolean hasTrustedOwnership(Task task) {
        return task.getOwnerAccountId() != null && task.getTenantId() != null && !task.getTenantId().isBlank();
    }
}
