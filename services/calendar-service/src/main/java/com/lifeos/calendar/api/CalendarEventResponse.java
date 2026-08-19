package com.lifeos.calendar.api;

import com.lifeos.calendar.domain.CalendarEvent;
import com.lifeos.calendar.domain.CalendarEventReminder;
import com.lifeos.calendar.domain.CalendarEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Versioned public calendar-event representation. */
public record CalendarEventResponse(
        UUID id,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        String timeZone,
        CalendarRecurrenceRequest recurrence,
        List<CalendarReminderResponse> reminders,
        CalendarEventStatus status,
        long version) {

    public static CalendarEventResponse from(CalendarEvent event, List<CalendarEventReminder> reminders) {
        return new CalendarEventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getStartAt(),
                event.getEndAt(),
                event.getTimeZone(),
                CalendarRecurrence.fromStored(event.getRecurrenceRule()),
                reminders.stream()
                        .map(value -> new CalendarReminderResponse(value.getMinutesBefore(), value.channels()))
                        .toList(),
                event.getStatus(),
                event.getVersion());
    }
}
