package com.lifeos.media.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lookup and locking of one actor- and resource-scoped idempotency reservation. */
public interface MediaMutationIdempotencyRepository extends JpaRepository<MediaMutationIdempotency, UUID> {

    Optional<MediaMutationIdempotency>
            findByActorAccountIdAndTenantIdAndOperationAndResourceScopeAndIdempotencyKeyHash(
                    UUID actorAccountId,
                    String tenantId,
                    String operation,
                    String resourceScope,
                    String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from MediaMutationIdempotency record where record.id = :id")
    Optional<MediaMutationIdempotency> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select record from MediaMutationIdempotency record
            where record.actorAccountId = :actorAccountId
              and record.tenantId = :tenantId
              and record.operation = :operation
              and record.resourceScope = :resourceScope
              and record.idempotencyKeyHash = :idempotencyKeyHash
            """)
    Optional<MediaMutationIdempotency> findByScopeForUpdate(
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") String operation,
            @Param("resourceScope") String resourceScope,
            @Param("idempotencyKeyHash") String idempotencyKeyHash);
}
