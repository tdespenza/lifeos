package com.lifeos.profile.idempotency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Locking operations for durable profile write reservations. */
public interface ProfileMutationIdempotencyRepository extends JpaRepository<ProfileMutationIdempotency, UUID> {

    Optional<ProfileMutationIdempotency> findByActorAccountIdAndTenantIdAndOperationAndIdempotencyKeyHash(
            UUID actorAccountId,
            String tenantId,
            ProfileMutationOperation operation,
            String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from ProfileMutationIdempotency record "
            + "where record.actorAccountId = :actorAccountId and record.tenantId = :tenantId "
            + "and record.operation = :operation and record.idempotencyKeyHash = :idempotencyKeyHash")
    Optional<ProfileMutationIdempotency> findByScopeAndKeyForUpdate(
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") ProfileMutationOperation operation,
            @Param("idempotencyKeyHash") String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from ProfileMutationIdempotency record "
            + "where record.id = :id and record.actorAccountId = :actorAccountId "
            + "and record.tenantId = :tenantId and record.operation = :operation")
    Optional<ProfileMutationIdempotency> findByIdAndScopeForUpdate(
            @Param("id") UUID id,
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") ProfileMutationOperation operation);
}
