package com.lifeos.calendar.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Indexed time-block persistence and half-open interval conflict queries. */
public interface CalendarTimeBlockRepository extends JpaRepository<CalendarTimeBlock, UUID> {

    List<CalendarTimeBlock> findByTenantIdAndOwnerAccountIdOrderByStartAtAscIdAsc(
            String tenantId, UUID ownerAccountId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select block from CalendarTimeBlock block where block.id = :id")
    Optional<CalendarTimeBlock> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select block from CalendarTimeBlock block
            where block.tenantId = :tenantId
              and block.ownerAccountId = :ownerAccountId
              and block.status = :status
              and block.startAt < :windowEnd
              and block.endAt > :windowStart
              and (:excludedId is null or block.id <> :excludedId)
            order by block.startAt asc, block.endAt asc, block.id asc
            """)
    List<CalendarTimeBlock> findActiveOverlapping(
            @Param("tenantId") String tenantId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("status") CalendarTimeBlockStatus status,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("excludedId") UUID excludedId,
            Pageable pageable);
}
