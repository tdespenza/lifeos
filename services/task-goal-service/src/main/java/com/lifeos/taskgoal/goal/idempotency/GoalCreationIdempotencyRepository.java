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

/** Database operations that make goal-create replay safe across service instances. */
public interface GoalCreationIdempotencyRepository extends JpaRepository<GoalCreationIdempotency, UUID> {

    Optional<GoalCreationIdempotency> findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
            UUID ownerAccountId, String tenantId, String idempotencyKeyHash);

    /**
     * Locks one scope-bound reservation while its goal is inspected or created. A finite timeout
     * prevents a stalled peer transaction from consuming request threads indefinitely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from GoalCreationIdempotency record "
            + "where record.id = :recordId "
            + "and record.ownerAccountId = :ownerAccountId "
            + "and record.tenantId = :tenantId")
    Optional<GoalCreationIdempotency> findByIdAndScopeForUpdate(
            @Param("recordId") UUID recordId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);
}
