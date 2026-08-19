package com.lifeos.taskgoal.goal.idempotency;

import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalLifecycleResult;
import com.lifeos.taskgoal.goal.GoalLifecycleTransitionException;
import com.lifeos.taskgoal.goal.GoalVersionConflictException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** Coordinates durable reservations and deterministic replay for every retryable lifecycle write. */
@Service
public class GoalMutationIdempotencyService {

    private final GoalMutationIdempotencyTransactions transactions;

    public GoalMutationIdempotencyService(GoalMutationIdempotencyTransactions transactions) {
        this.transactions = transactions;
    }

    /**
     * Applies or replays one actor-scoped lifecycle command.
     *
     * <p>The target was authorized before this method is called. Every retry still goes through
     * that authorization step in {@code GoalService}; the reservation never becomes a bypass for
     * a revoked session or changed policy.
     */
    public GoalLifecycleResult execute(
            UUID actorAccountId,
            String tenantId,
            Goal goal,
            GoalMutationOperation operation,
            long expectedVersion,
            String idempotencyKey,
            String title) {
        return execute(actorAccountId, tenantId, goal, operation, expectedVersion, idempotencyKey, title, 3, null);
    }

    public GoalLifecycleResult execute(
            UUID actorAccountId,
            String tenantId,
            Goal goal,
            GoalMutationOperation operation,
            long expectedVersion,
            String idempotencyKey,
            String title,
            int priority,
            java.time.Instant dueAt) {
        String key = GoalIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = GoalMutationFingerprint.keyHash(key);
        String requestFingerprint = priority == 3 && dueAt == null
                ? GoalMutationFingerprint.requestFingerprint(goal.getId(), operation, expectedVersion, title)
                : GoalMutationFingerprint.requestFingerprint(
                        goal.getId(), operation, expectedVersion, title, priority, dueAt);
        GoalMutationIdempotency reservation = reserveOrLoad(
                actorAccountId,
                tenantId,
                goal.getId(),
                operation,
                keyHash,
                requestFingerprint,
                expectedVersion);

        if (!reservation.matchesRequestFingerprint(requestFingerprint)
                || reservation.getExpectedVersion() != expectedVersion) {
            throw new GoalIdempotencyConflictException();
        }

        try {
            if (priority == 3 && dueAt == null) {
                return transactions.complete(
                        reservation.getId(),
                        actorAccountId,
                        tenantId,
                        goal.getId(),
                        operation,
                        requestFingerprint,
                        expectedVersion,
                        title);
            }
            return transactions.complete(
                    reservation.getId(),
                    actorAccountId,
                    tenantId,
                    goal.getId(),
                    operation,
                    requestFingerprint,
                    expectedVersion,
                    title,
                    priority,
                    dueAt);
        } catch (GoalIdempotencyConflictException
                | GoalIdempotencyUnavailableException
                | GoalVersionConflictException
                | GoalLifecycleTransitionException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new GoalIdempotencyUnavailableException();
        }
    }

    private GoalMutationIdempotency reserveOrLoad(
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String keyHash,
            String requestFingerprint,
            long expectedVersion) {
        Optional<GoalMutationIdempotency> existing = findExisting(
                actorAccountId, tenantId, goalId, operation, keyHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactions.reserve(
                    actorAccountId,
                    tenantId,
                    goalId,
                    operation,
                    keyHash,
                    requestFingerprint,
                    expectedVersion);
        } catch (DataIntegrityViolationException exception) {
            return findExisting(actorAccountId, tenantId, goalId, operation, keyHash)
                    .orElseThrow(GoalIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new GoalIdempotencyUnavailableException();
        }
    }

    private Optional<GoalMutationIdempotency> findExisting(
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String keyHash) {
        try {
            return transactions.findExisting(actorAccountId, tenantId, goalId, operation, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new GoalIdempotencyUnavailableException();
        }
    }
}
