package com.lifeos.calendar.reminder;

import java.time.Instant;

/** One materialized recurrence interval. */
public record CalendarOccurrenceWindow(Instant startAt, Instant endAt) {
}
