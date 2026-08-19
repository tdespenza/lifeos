package com.lifeos.calendar.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Calendar event persistence operations with short write locks for versioned lifecycle changes. */
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {

    long countByTenantIdAndOwnerAccountId(String tenantId, UUID ownerAccountId);

    List<CalendarEvent> findByTenantIdAndOwnerAccountIdOrderByStartAtAscIdAsc(
            String tenantId, UUID ownerAccountId, Pageable pageable);

    @Query("""
            select event from CalendarEvent event
            where event.status = :status
              and event.recurrenceRule is not null
              and (event.recurrenceNextMaterializationAt is null or event.recurrenceNextMaterializationAt <= :now)
            order by event.recurrenceNextMaterializationAt asc, event.id asc
            """)
    List<CalendarEvent> findMaterializationDue(
            @Param("status") CalendarEventStatus status, @Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from CalendarEvent event where event.id = :id")
    Optional<CalendarEvent> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select event from CalendarEvent event
            where event.tenantId = :tenantId
              and event.ownerAccountId = :ownerAccountId
              and event.status = :status
              and event.startAt < :windowEnd
              and event.endAt > :windowStart
            order by event.startAt asc, event.endAt asc, event.id asc
            """)
    List<CalendarEvent> findActiveOverlapping(
            @Param("tenantId") String tenantId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("status") CalendarEventStatus status,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);
}
