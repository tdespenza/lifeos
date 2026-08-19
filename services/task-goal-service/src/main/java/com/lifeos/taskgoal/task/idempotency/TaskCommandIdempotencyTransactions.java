package com.lifeos.taskgoal.task.idempotency;

import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskLifecycleTransitionException;
import com.lifeos.taskgoal.task.TaskRepository;
import com.lifeos.taskgoal.task.TaskVersionConflictException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundaries for durable Task command reservations and their replay snapshots.
 *
 * <p>Reservation is independently committed. The completion transaction locks the reservation,
 * performs at most one Task write, and stores the immutable result in the same commit.
 */
@Service
public class TaskCommandIdempotencyTransactions {

    public static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final TaskCommandIdempotencyRepository idempotencyRepository;
    private final TaskRepository taskRepository;

    public TaskCommandIdempotencyTransactions(
            TaskCommandIdempotencyRepository idempotencyRepository, TaskRepository taskRepository) {
        this.idempotencyRepository = idempotencyRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public TaskCommandIdempotency reserve(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            UUID taskId,
            String idempotencyKeyHash,
            String requestFingerprint,
            Long expectedVersion) {
        return idempotencyRepository.saveAndFlush(new TaskCommandIdempotency(
                actorAccountId,
                tenantId,
                operation,
                targetScope,
                taskId,
                idempotencyKeyHash,
                requestFingerprint,
                expectedVersion));
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<TaskCommandIdempotency> findExisting(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            String idempotencyKeyHash) {
        return idempotencyRepository.findByActorAccountIdAndTenantIdAndOperationAndTargetScopeAndIdempotencyKeyHash(
                actorAccountId, tenantId, operation, targetScope, idempotencyKeyHash);
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public TaskLifecycleResult complete(
            UUID reservationId,
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String requestFingerprint,
            Long expectedVersion,
            String title) {
        return complete(
                reservationId,
                actorAccountId,
                tenantId,
                operation,
                requestFingerprint,
                expectedVersion,
                title,
                null,
                null);
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public TaskLifecycleResult complete(
            UUID reservationId,
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String requestFingerprint,
            Long expectedVersion,
            String title,
            Integer priority,
            java.time.Instant dueAt) {
        TaskCommandIdempotency reservation = idempotencyRepository
                .findByIdAndScopeForUpdate(reservationId, actorAccountId, tenantId)
                .orElseThrow(TaskIdempotencyUnavailableException::new);
        if (reservation.getOperation() != operation
                || !reservation.matchesRequestFingerprint(requestFingerprint)
                || hasDifferentNullness(reservation.getExpectedVersion(), expectedVersion)
                || (expectedVersion != null && !expectedVersion.equals(reservation.getExpectedVersion()))) {
            throw new TaskIdempotencyConflictException();
        }
        if (reservation.isCompleted()) {
            return reservation.result();
        }

        TaskLifecycleResult result = switch (operation) {
            case CREATE -> create(
                    reservation, actorAccountId, tenantId, title, priority == null ? 3 : priority, dueAt);
            case UPDATE, COMPLETE, CANCEL -> mutate(
                    reservation, actorAccountId, tenantId, operation, expectedVersion, title, priority, dueAt);
        };
        reservation.complete(result);
        return result;
    }

    private TaskLifecycleResult create(
            TaskCommandIdempotency reservation,
            UUID actorAccountId,
            String tenantId,
            String title,
            Integer priority,
            java.time.Instant dueAt) {
        if (title == null) {
            throw new TaskIdempotencyUnavailableException();
        }
        Task task = taskRepository.findById(reservation.getTaskId()).orElse(null);
        if (task == null) {
            task = taskRepository.saveAndFlush(new Task(
                    reservation.getTaskId(), title, actorAccountId, tenantId, priority, dueAt));
        } else if (!isScopeMatch(task, actorAccountId, tenantId)) {
            throw new TaskIdempotencyUnavailableException();
        }
        return TaskLifecycleResult.from(task);
    }

    private TaskLifecycleResult mutate(
            TaskCommandIdempotency reservation,
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            Long expectedVersion,
            String title,
            Integer priority,
            java.time.Instant dueAt) {
        if (expectedVersion == null) {
            throw new TaskIdempotencyConflictException();
        }
        Task task = taskRepository
                .findByIdForUpdate(reservation.getTaskId())
                .orElseThrow(TaskIdempotencyUnavailableException::new);
        if (!isScopeMatch(task, actorAccountId, tenantId)) {
            throw new TaskIdempotencyUnavailableException();
        }
        if (task.getVersion() != expectedVersion) {
            throw new TaskVersionConflictException();
        }
        try {
            switch (operation) {
                case UPDATE -> {
                    if (priority == null) {
                        task.rename(title);
                    } else {
                        task.updatePlanning(title, priority, dueAt);
                    }
                }
                case COMPLETE -> task.complete();
                case CANCEL -> task.cancel();
                case CREATE -> throw new IllegalStateException("create must use its dedicated path");
            }
            return TaskLifecycleResult.from(taskRepository.saveAndFlush(task));
        } catch (OptimisticLockingFailureException exception) {
            throw new TaskVersionConflictException();
        } catch (TaskLifecycleTransitionException exception) {
            throw exception;
        }
    }

    private static boolean isScopeMatch(Task task, UUID actorAccountId, String tenantId) {
        return actorAccountId.equals(task.getOwnerAccountId()) && tenantId.equals(task.getTenantId());
    }

    private static boolean hasDifferentNullness(Long first, Long second) {
        return (first == null) != (second == null);
    }
}
