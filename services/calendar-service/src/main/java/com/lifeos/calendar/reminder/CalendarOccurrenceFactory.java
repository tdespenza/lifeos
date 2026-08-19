package com.lifeos.calendar.reminder;

import com.lifeos.calendar.domain.CalendarEvent;
import com.lifeos.calendar.domain.CalendarEventReminderRepository;
import com.lifeos.calendar.domain.CalendarOccurrence;
import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import com.lifeos.calendar.domain.CalendarReminder;
import com.lifeos.calendar.domain.CalendarReminderRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Creates an occurrence and all of its durable reminder rows in the caller's transaction. */
@Service
public class CalendarOccurrenceFactory {

    private final CalendarOccurrenceRepository occurrenceRepository;
    private final CalendarEventReminderRepository eventReminderRepository;
    private final CalendarReminderRepository reminderRepository;

    public CalendarOccurrenceFactory(
            CalendarOccurrenceRepository occurrenceRepository,
            CalendarEventReminderRepository eventReminderRepository,
            CalendarReminderRepository reminderRepository) {
        this.occurrenceRepository = occurrenceRepository;
        this.eventReminderRepository = eventReminderRepository;
        this.reminderRepository = reminderRepository;
    }

    /** Persists one occurrence and emits no notification yet; due scheduling performs the outbox transition later. */
    public CalendarOccurrence create(
            CalendarEvent event, long recurrenceRevision, Instant startAt, Instant endAt, Instant now) {
        CalendarOccurrence occurrence = occurrenceRepository.save(CalendarOccurrence.active(
                UUID.randomUUID(), event, recurrenceRevision, startAt, endAt, now));
        eventReminderRepository.findByEventIdOrderByMinutesBeforeAsc(event.getId()).forEach(template ->
                reminderRepository.save(CalendarReminder.scheduled(
                        UUID.randomUUID(),
                        occurrence,
                        template.getMinutesBefore(),
                        template.channels(),
                        UUID.randomUUID(),
                        now)));
        return occurrence;
    }
}
