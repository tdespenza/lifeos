package com.lifeos.taskgoal.goal.idempotency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Durable reservation lookup and lock operations for goal lifecycle mutations. */
public interface GoalMutationIdempotencyRepository extends JpaRepository<GoalMutationIdempotency, UUID> {

    Optional<GoalMutationIdempotency> findByActorAccountIdAndTenantIdAndGoalIdAndOperationAndIdempotencyKeyHash(
            UUID actorAccountId,
            String tenantId,
            UUID goalId,
            GoalMutationOperation operation,
            String idempotencyKeyHash);

    /** Locks one scoped reservation while it is completed or replayed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from GoalMutationIdempotency record "
            + "where record.id = :recordId "
            + "and record.actorAccountId = :actorAccountId "
            + "and record.tenantId = :tenantId "
            + "and record.goalId = :goalId "
            + "and record.operation = :operation")
    Optional<GoalMutationIdempotency> findByIdAndScopeForUpdate(
            @Param("recordId") UUID recordId,
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("goalId") UUID goalId,
            @Param("operation") GoalMutationOperation operation);
}
