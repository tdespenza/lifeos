package com.lifeos.finance.idempotency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Locking operations for Finance durable retry reservations. */
public interface FinanceMutationIdempotencyRepository extends JpaRepository<FinanceMutationIdempotency, UUID> {

    Optional<FinanceMutationIdempotency> findByActorAccountIdAndTenantIdAndOperationAndIdempotencyKeyHash(
            UUID actorAccountId,
            String tenantId,
            FinanceMutationOperation operation,
            String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select reservation from FinanceMutationIdempotency reservation "
            + "where reservation.actorAccountId = :actorAccountId and reservation.tenantId = :tenantId "
            + "and reservation.operation = :operation and reservation.idempotencyKeyHash = :idempotencyKeyHash")
    Optional<FinanceMutationIdempotency> findByScopeAndKeyForUpdate(
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") FinanceMutationOperation operation,
            @Param("idempotencyKeyHash") String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select reservation from FinanceMutationIdempotency reservation where reservation.id = :id "
            + "and reservation.actorAccountId = :actorAccountId and reservation.tenantId = :tenantId "
            + "and reservation.operation = :operation")
    Optional<FinanceMutationIdempotency> findByIdAndScopeForUpdate(
            @Param("id") UUID id,
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") FinanceMutationOperation operation);
}
