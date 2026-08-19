package com.lifeos.taskgoal.goal;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    List<Goal> findAllByOwnerAccountIdAndTenantId(UUID ownerAccountId, String tenantId);

    List<Goal> findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(UUID ownerAccountId, String tenantId);

    Optional<Goal> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);

    /**
     * Serializes different idempotency keys targeting the same lifecycle transition.
     *
     * <p>The finite lock wait bounds a failed peer transaction; callers map that condition to a
     * retryable response instead of keeping virtual request threads blocked indefinitely.
    */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select goal from Goal goal where goal.id = :id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<Goal> findByIdForUpdate(@Param("id") UUID id);
}
