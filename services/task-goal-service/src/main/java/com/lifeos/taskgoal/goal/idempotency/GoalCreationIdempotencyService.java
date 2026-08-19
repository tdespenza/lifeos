package com.lifeos.taskgoal.goal.idempotency;

import com.lifeos.taskgoal.goal.Goal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** Coordinates bounded request validation, durable reservation, and a single goal creation. */
@Service
public class GoalCreationIdempotencyService {

    private final GoalCreationIdempotencyTransactions transactions;

    public GoalCreationIdempotencyService(GoalCreationIdempotencyTransactions transactions) {
        this.transactions = transactions;
    }

    /**
     * Creates or replays a goal for exactly one validated account/tenant/key/payload tuple.
     *
     * @param ownerAccountId trusted account identifier from the validated subject
     * @param tenantId trusted tenant identifier from the validated subject
     * @param idempotencyKey opaque client retry key
     * @param title validated goal title
     * @param proposedGoalId identifier authorized for a new goal before the reservation is made
     * @return the originally created goal for both first submission and matching retries
     */
    public Goal createOrReplay(
            UUID ownerAccountId,
            String tenantId,
            String idempotencyKey,
            String title,
            UUID proposedGoalId) {
        return createOrReplay(ownerAccountId, tenantId, idempotencyKey, title, 3, null, proposedGoalId);
    }

    public Goal createOrReplay(
            UUID ownerAccountId,
            String tenantId,
            String idempotencyKey,
            String title,
            int priority,
            java.time.Instant dueAt,
            UUID proposedGoalId) {
        String key = GoalIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = GoalCreationFingerprint.keyHash(key);
        String requestFingerprint = priority == 3 && dueAt == null
                ? GoalCreationFingerprint.requestFingerprint(title)
                : GoalCreationFingerprint.requestFingerprint(title, priority, dueAt);
        GoalCreationIdempotency reservation = reserveOrLoad(
                ownerAccountId, tenantId, keyHash, requestFingerprint, proposedGoalId);

        if (!reservation.matchesRequestFingerprint(requestFingerprint)) {
            throw new GoalIdempotencyConflictException();
        }

        try {
            if (priority == 3 && dueAt == null) {
                return transactions.complete(reservation.getId(), ownerAccountId, tenantId, requestFingerprint, title);
            }
            return transactions.complete(
                    reservation.getId(), ownerAccountId, tenantId, requestFingerprint, title, priority, dueAt);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            // A caller can retry this same key after a bounded database lock or transport failure.
            // Do not expose persistence details, keys, hashes, or another tenant's goal.
            throw new GoalIdempotencyUnavailableException();
        }
    }

    private GoalCreationIdempotency reserveOrLoad(
            UUID ownerAccountId,
            String tenantId,
            String keyHash,
            String requestFingerprint,
            UUID proposedGoalId) {
        Optional<GoalCreationIdempotency> existing = findExisting(ownerAccountId, tenantId, keyHash);
        if (existing.isPresent()) {
            // The normal retry path must not intentionally violate the unique constraint. Besides
            // avoiding avoidable database work, this keeps expected replays out of constraint
            // violation logs that can include scope/key-hash metadata.
            return existing.get();
        }

        try {
            return transactions.reserve(ownerAccountId, tenantId, keyHash, requestFingerprint, proposedGoalId);
        } catch (DataIntegrityViolationException exception) {
            // The lookup and insert race across instances. PostgreSQL resolves the collision only
            // after the winner commits or rolls back, so a collision has one durable winner to
            // load. This remains a rare race fallback, not the ordinary replay mechanism.
            return findExisting(ownerAccountId, tenantId, keyHash)
                    .orElseThrow(GoalIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new GoalIdempotencyUnavailableException();
        }
    }

    private Optional<GoalCreationIdempotency> findExisting(
            UUID ownerAccountId, String tenantId, String keyHash) {
        try {
            return transactions.findExisting(ownerAccountId, tenantId, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new GoalIdempotencyUnavailableException();
        }
    }
}
