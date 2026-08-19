package com.lifeos.taskgoal.goal.idempotency;

import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundaries for durable goal-create idempotency.
 *
 * <p>Reservation is committed independently so a crash before goal persistence leaves a stable
 * identifier for a later matching retry. Completion locks that reservation and commits the goal
 * row and completed state atomically.
 */
@Service
public class GoalCreationIdempotencyTransactions {

    /** Upper bound for reservation and completion database work, including lock waits. */
    public static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final GoalCreationIdempotencyRepository idempotencyRepository;
    private final GoalRepository goalRepository;

    public GoalCreationIdempotencyTransactions(
            GoalCreationIdempotencyRepository idempotencyRepository, GoalRepository goalRepository) {
        this.idempotencyRepository = idempotencyRepository;
        this.goalRepository = goalRepository;
    }

    /**
     * Atomically claims an account/tenant/key tuple before any goal is inserted.
     *
     * <p>A unique-constraint collision is intentionally allowed to escape this new transaction;
     * the caller then loads the winning durable reservation in a clean transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public GoalCreationIdempotency reserve(
            UUID ownerAccountId,
            String tenantId,
            String idempotencyKeyHash,
            String requestFingerprint,
            UUID goalId) {
        return idempotencyRepository.saveAndFlush(new GoalCreationIdempotency(
                ownerAccountId, tenantId, idempotencyKeyHash, requestFingerprint, goalId));
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<GoalCreationIdempotency> findExisting(
            UUID ownerAccountId, String tenantId, String idempotencyKeyHash) {
        return idempotencyRepository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
                ownerAccountId, tenantId, idempotencyKeyHash);
    }

    /**
     * Completes a claimed request under a row lock. Concurrent matching retries serialize here,
     * then all return the same persisted goal. A pending reservation with no goal is safely
     * recovered using its pre-allocated identifier.
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Goal complete(
            UUID reservationId,
            UUID ownerAccountId,
            String tenantId,
            String requestFingerprint,
            String title) {
        return complete(reservationId, ownerAccountId, tenantId, requestFingerprint, title, 3, null);
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Goal complete(
            UUID reservationId,
            UUID ownerAccountId,
            String tenantId,
            String requestFingerprint,
            String title,
            int priority,
            java.time.Instant dueAt) {
        GoalCreationIdempotency reservation = idempotencyRepository
                .findByIdAndScopeForUpdate(reservationId, ownerAccountId, tenantId)
                .orElseThrow(GoalIdempotencyUnavailableException::new);
        if (!reservation.matchesRequestFingerprint(requestFingerprint)) {
            throw new GoalIdempotencyConflictException();
        }

        Goal existingGoal = goalRepository.findById(reservation.getGoalId()).orElse(null);
        if (existingGoal != null) {
            if (!matchesReservedGoalScope(existingGoal, ownerAccountId, tenantId)) {
                // A mismatched row can only be operator/data corruption or an astronomically
                // unlikely UUID collision. Do not reveal or return a potentially foreign goal.
                throw new GoalIdempotencyUnavailableException();
            }
            if (!reservation.isCompleted() && !Objects.equals(existingGoal.getTitle(), title)) {
                // A pending reservation must still correspond to its original create request.
                // A completed reservation may legitimately point to a goal later renamed by a
                // distinct lifecycle command, in which case a create retry returns that current
                // representation while retaining the original resource identity and Location.
                throw new GoalIdempotencyUnavailableException();
            }
            reservation.complete();
            return existingGoal;
        }
        if (reservation.isCompleted()) {
            // A completed record without its immutable goal is a data-integrity incident. Never
            // silently recreate it, because doing so could contradict a future deletion policy.
            throw new GoalIdempotencyUnavailableException();
        }

        Goal created = goalRepository.save(new Goal(
                reservation.getGoalId(), title, ownerAccountId, tenantId, priority, dueAt));
        reservation.complete();
        return created;
    }

    private static boolean matchesReservedGoalScope(Goal goal, UUID ownerAccountId, String tenantId) {
        return Objects.equals(goal.getOwnerAccountId(), ownerAccountId)
                && Objects.equals(goal.getTenantId(), tenantId);
    }
}
