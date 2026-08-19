package com.lifeos.calendar.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lease-safe due-reminder claims; PostgreSQL executes this with SKIP LOCKED across replicas. */
public interface CalendarReminderRepository extends JpaRepository<CalendarReminder, UUID> {

    List<CalendarReminder> findByEventIdAndStateIn(UUID eventId, List<CalendarReminderState> states);

    List<CalendarReminder> findByEventId(UUID eventId);

    @Query(
            value = """
                    select * from calendar_reminder
                    where (state = 'SCHEDULED' and due_at <= :now)
                       or (state = 'LEASED' and lease_expires_at <= :now)
                    order by due_at asc, id asc
                    limit :limit for update skip locked
                    """,
            nativeQuery = true)
    List<CalendarReminder> findClaimableForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
