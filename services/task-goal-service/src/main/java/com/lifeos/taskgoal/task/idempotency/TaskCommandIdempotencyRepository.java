package com.lifeos.taskgoal.task.idempotency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Reservation lookup and bounded lock operations shared by all Task commands. */
public interface TaskCommandIdempotencyRepository extends JpaRepository<TaskCommandIdempotency, UUID> {

    Optional<TaskCommandIdempotency> findByActorAccountIdAndTenantIdAndOperationAndTargetScopeAndIdempotencyKeyHash(
            UUID actorAccountId,
            String tenantId,
            TaskCommandOperation operation,
            String targetScope,
            String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from TaskCommandIdempotency record "
            + "where record.id = :recordId "
            + "and record.actorAccountId = :actorAccountId "
            + "and record.tenantId = :tenantId")
    Optional<TaskCommandIdempotency> findByIdAndScopeForUpdate(
            @Param("recordId") UUID recordId,
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId);
}
