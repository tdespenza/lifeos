package com.lifeos.taskgoal.task.idempotency;

import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskLifecycleTransitionException;
import com.lifeos.taskgoal.task.TaskVersionConflictException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** Coordinates exact Task command replay, crash recovery, and bounded persistence failures. */
@Service
public class TaskCommandIdempotencyService {

    private static final String CREATE_SCOPE = "create";

    private final TaskCommandIdempotencyTransactions transactions;

    public TaskCommandIdempotencyService(TaskCommandIdempotencyTransactions transactions) {
        this.transactions = transactions;
    }

    public TaskLifecycleResult createOrReplay(
            UUID actorAccountId, String tenantId, UUID proposedTaskId, String title, String idempotencyKey) {
        return createOrReplay(actorAccountId, tenantId, proposedTaskId, title, 3, null, idempotencyKey);
    }

    public TaskLifecycleResult createOrReplay(
            UUID actorAccountId,
            String tenantId,
            UUID proposedTaskId,
            String title,
            int priority,
            java.time.Instant dueAt,
            String idempotencyKey) {
        return execute(
                actorAccountId,
                tenantId,
                TaskCommandOperation.CREATE,
                CREATE_SCOPE,
                proposedTaskId,
                null,
                title,
                priority,
                dueAt,
                idempotencyKey);
    }

    public TaskLifecycleResult mutateOrReplay(
            UUID actorAccountId,
            String tenantId,
            Task task,
                TaskCommandOperation operation,
                long expectedVersion,
                String title,
                String idempotencyKey) {
        return mutateOrReplay(actorAccountId, tenantId, task, operation, expectedVersion, title, 3, null, idempotencyKey);
    }

    public TaskLifecycleResult mutateOrReplay(
            UUID actorAccountId,
            String tenantId,
            Task task,
            TaskCommandOperation operation,
            long expectedVersion,
            String title,
            int priority,
            java.time.Instant dueAt,
            String idempotencyKey) {
        if (operation == TaskCommandOperation.CREATE) {
            throw new IllegalArgumentException("create must use createOrReplay");
        }
        return execute(
                actorAccountId,
                tenantId,
                operation,
                task.getId().toString(),
                task.getId(),
                expectedVersion,
                title,
                priority,
                dueAt,
                idempotencyKey);
    }

    private TaskLifecycleResult execute(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            UUID proposedTaskId,
            Long expectedVersion,
            String title,
            int priority,
            java.time.Instant dueAt,
            String idempotencyKey) {
        String keyHash = TaskCommandFingerprint.keyHash(TaskIdempotencyKey.requireValid(idempotencyKey));
        String requestFingerprint = priority == 3 && dueAt == null
                ? TaskCommandFingerprint.requestFingerprint(proposedTaskId, operation, expectedVersion, title)
                : TaskCommandFingerprint.requestFingerprint(
                        proposedTaskId, operation, expectedVersion, title, priority, dueAt);
        TaskCommandIdempotency reservation = reserveOrLoad(
                actorAccountId,
                tenantId,
                operation,
                targetScope,
                proposedTaskId,
                keyHash,
                requestFingerprint,
                expectedVersion);
        if (!reservation.matchesRequestFingerprint(requestFingerprint)
                || hasDifferentNullness(reservation.getExpectedVersion(), expectedVersion)
                || (expectedVersion != null && !expectedVersion.equals(reservation.getExpectedVersion()))) {
            throw new TaskIdempotencyConflictException();
        }
        try {
            if (priority == 3 && dueAt == null) {
                return transactions.complete(
                        reservation.getId(),
                        actorAccountId,
                        tenantId,
                        operation,
                        requestFingerprint,
                        expectedVersion,
                        title);
            }
            return transactions.complete(
                    reservation.getId(),
                    actorAccountId,
                    tenantId,
                    operation,
                    requestFingerprint,
                    expectedVersion,
                    title,
                    priority,
                    dueAt);
        } catch (TaskIdempotencyConflictException
                | TaskIdempotencyUnavailableException
                | TaskVersionConflictException
                | TaskLifecycleTransitionException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new TaskIdempotencyUnavailableException();
        }
    }

    private TaskCommandIdempotency reserveOrLoad(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            UUID proposedTaskId,
            String keyHash,
            String requestFingerprint,
            Long expectedVersion) {
        Optional<TaskCommandIdempotency> existing = findExisting(
                actorAccountId, tenantId, operation, targetScope, keyHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactions.reserve(
                    actorAccountId,
                    tenantId,
                    operation,
                    targetScope,
                    proposedTaskId,
                    keyHash,
                    requestFingerprint,
                    expectedVersion);
        } catch (DataIntegrityViolationException exception) {
            return findExisting(actorAccountId, tenantId, operation, targetScope, keyHash)
                    .orElseThrow(TaskIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new TaskIdempotencyUnavailableException();
        }
    }

    private Optional<TaskCommandIdempotency> findExisting(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            String keyHash) {
        try {
            return transactions.findExisting(actorAccountId, tenantId, operation, targetScope, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new TaskIdempotencyUnavailableException();
        }
    }

    private static boolean hasDifferentNullness(Long first, Long second) {
        return (first == null) != (second == null);
    }
}
