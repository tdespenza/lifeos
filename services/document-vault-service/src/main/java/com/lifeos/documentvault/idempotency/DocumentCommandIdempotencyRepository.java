package com.lifeos.documentvault.idempotency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Locking reads for durable command reservation and exact replay. */
public interface DocumentCommandIdempotencyRepository extends JpaRepository<DocumentCommandIdempotency, UUID> {

    Optional<DocumentCommandIdempotency> findByActorAccountIdAndTenantIdAndOperationAndIdempotencyKeyHash(
            UUID actorAccountId,
            String tenantId,
            DocumentCommandOperation operation,
            String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from DocumentCommandIdempotency record "
            + "where record.id = :id and record.actorAccountId = :actorAccountId "
            + "and record.tenantId = :tenantId and record.operation = :operation")
    Optional<DocumentCommandIdempotency> findByIdAndScopeForUpdate(
            @Param("id") UUID id,
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") DocumentCommandOperation operation);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from DocumentCommandIdempotency record "
            + "where record.actorAccountId = :actorAccountId and record.tenantId = :tenantId "
            + "and record.operation = :operation and record.idempotencyKeyHash = :idempotencyKeyHash")
    Optional<DocumentCommandIdempotency> findByScopeAndKeyForUpdate(
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") DocumentCommandOperation operation,
            @Param("idempotencyKeyHash") String idempotencyKeyHash);
}
