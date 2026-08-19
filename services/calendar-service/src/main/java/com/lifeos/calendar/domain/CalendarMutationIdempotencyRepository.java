package com.lifeos.calendar.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Durable lookup for the scoped HMAC idempotency reservation. */
public interface CalendarMutationIdempotencyRepository extends JpaRepository<CalendarMutationIdempotency, UUID> {

    Optional<CalendarMutationIdempotency>
            findByActorAccountIdAndTenantIdAndOperationAndResourceScopeAndIdempotencyKeyHash(
                    UUID actorAccountId,
                    String tenantId,
                    String operation,
                    String resourceScope,
                    String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from CalendarMutationIdempotency record where record.id = :id")
    Optional<CalendarMutationIdempotency> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select record from CalendarMutationIdempotency record
            where record.actorAccountId = :actorAccountId
              and record.tenantId = :tenantId
              and record.operation = :operation
              and record.resourceScope = :resourceScope
              and record.idempotencyKeyHash = :idempotencyKeyHash
            """)
    Optional<CalendarMutationIdempotency> findByScopeForUpdate(
            @Param("actorAccountId") UUID actorAccountId,
            @Param("tenantId") String tenantId,
            @Param("operation") String operation,
            @Param("resourceScope") String resourceScope,
            @Param("idempotencyKeyHash") String idempotencyKeyHash);
}
