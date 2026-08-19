package com.lifeos.calendar.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lease-safe producer outbox claims. */
public interface CalendarOutboxEventRepository extends JpaRepository<CalendarOutboxEvent, UUID> {

    List<CalendarOutboxEvent> findByReminderIdIn(List<UUID> reminderIds);

    @Query(
            value = """
                    select * from calendar_outbox_event
                    where (state = 'PENDING' and available_at <= :now)
                       or (state = 'IN_FLIGHT' and lease_expires_at <= :now)
                    order by available_at asc, id asc
                    limit :limit for update skip locked
                    """,
            nativeQuery = true)
    List<CalendarOutboxEvent> findClaimableForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
