package com.lifeos.calendar.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Event-level reminder templates. */
public interface CalendarEventReminderRepository extends JpaRepository<CalendarEventReminder, UUID> {

    List<CalendarEventReminder> findByEventIdOrderByMinutesBeforeAsc(UUID eventId);

    void deleteByEventId(UUID eventId);
}
