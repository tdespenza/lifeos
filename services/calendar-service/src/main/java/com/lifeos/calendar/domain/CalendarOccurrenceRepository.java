package com.lifeos.calendar.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Indexed materialized-occurrence access for bounded reminders and conflict queries. */
public interface CalendarOccurrenceRepository extends JpaRepository<CalendarOccurrence, UUID> {

    List<CalendarOccurrence> findByEventIdAndRecurrenceRevision(UUID eventId, long recurrenceRevision);

    List<CalendarOccurrence> findByEventId(UUID eventId);

    @Query("""
            select occurrence from CalendarOccurrence occurrence
            where occurrence.tenantId = :tenantId
              and occurrence.ownerAccountId = :ownerAccountId
              and occurrence.status = :status
              and occurrence.startAt < :windowEnd
              and occurrence.endAt > :windowStart
            order by occurrence.startAt asc, occurrence.endAt asc, occurrence.id asc
            """)
    List<CalendarOccurrence> findActiveOverlapping(
            @Param("tenantId") String tenantId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("status") CalendarOccurrenceStatus status,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            Pageable pageable);
}
