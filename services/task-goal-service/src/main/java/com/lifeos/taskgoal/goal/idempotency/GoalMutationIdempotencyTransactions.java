package com.lifeos.taskgoal.goal.idempotency;

import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalLifecycleResult;
import com.lifeos.taskgoal.goal.GoalLifecycleTransitionException;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.goal.GoalVersionConflictException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundaries for durable update, complete, and archive idempotency. */
@Service
public class GoalMutationIdempotencyTransactions {

    /** Upper bound for a reservation, row lock, and completed mutation transaction. */
    public static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final GoalMutationIdempotencyRepository idempotencyRepository;
    private final GoalRepository goalRepository;

    public GoalMutationIdempotencyTransactions(
            GoalMutationIdempotencyRepository idempotencyRepository, GoalRepository goalRepository) {
        this.idempotencyRepository = idempotencyRepository;
        this.goalRepository = goalRepository;
    }

    /** Commits a caller-scoped reservation before the lifecycle command runs. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public GoalMutationIdempotency reserve(
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String idempotencyKeyHash,
            String requestFingerprint,
            long expectedVersion) {
        return idempotencyRepository.saveAndFlush(new GoalMutationIdempotency(
                actorAccountId,
                tenantId,
                goalId,
                operation,
                idempotencyKeyHash,
                requestFingerprint,
                expectedVersion));
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<GoalMutationIdempotency> findExisting(
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String idempotencyKeyHash) {
        return idempotencyRepository.findByActorAccountIdAndTenantIdAndGoalIdAndOperationAndIdempotencyKeyHash(
                actorAccountId, tenantId, goalId, operation, idempotencyKeyHash);
    }

    /**
     * Applies one lifecycle command, then saves an immutable replay snapshot in the same commit.
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public GoalLifecycleResult complete(
            UUID reservationId,
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String requestFingerprint,
            long expectedVersion,
            String title) {
        return complete(
                reservationId,
                actorAccountId,
                tenantId,
                goalId,
                operation,
                requestFingerprint,
                expectedVersion,
                title,
                null,
                null);
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public GoalLifecycleResult complete(
            UUID reservationId,
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String requestFingerprint,
            long expectedVersion,
            String title,
            Integer priority,
            java.time.Instant dueAt) {
        GoalMutationIdempotency reservation = idempotencyRepository
                .findByIdAndScopeForUpdate(reservationId, actorAccountId, tenantId, goalId, operation)
                .orElseThrow(GoalIdempotencyUnavailableException::new);
        if (!reservation.matchesRequestFingerprint(requestFingerprint)
                || reservation.getExpectedVersion() != expectedVersion) {
            throw new GoalIdempotencyConflictException();
        }
        if (reservation.isCompleted()) {
            return reservation.result();
        }

        Goal goal = goalRepository.findByIdForUpdate(goalId).orElseThrow(GoalIdempotencyUnavailableException::new);
        if (!tenantId.equals(goal.getTenantId()) || goal.getOwnerAccountId() == null) {
            // Authorization was completed before reservation. Ownership is immutable; a mismatch
            // here therefore indicates data corruption or an unsafe concurrent maintenance path.
            throw new GoalIdempotencyUnavailableException();
        }
        if (goal.getVersion() != expectedVersion) {
            throw new GoalVersionConflictException();
        }

        try {
            switch (operation) {
                case UPDATE -> {
                    if (priority == null) {
                        goal.rename(title);
                    } else {
                        goal.updatePlanning(title, priority, dueAt);
                    }
                }
                case COMPLETE -> goal.complete();
                case ARCHIVE -> goal.archive();
            }
            Goal flushed = goalRepository.saveAndFlush(goal);
            GoalLifecycleResult result = GoalLifecycleResult.from(flushed);
            reservation.complete(result);
            return result;
        } catch (OptimisticLockingFailureException exception) {
            throw new GoalVersionConflictException();
        } catch (GoalLifecycleTransitionException exception) {
            throw exception;
        }
    }
}
