package com.lifeos.calendar.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Replacement input for the current event series and its future reminders. */
public record UpdateCalendarEventRequest(
        @NotBlank @Size(max = 140) String title,
        @Size(max = 4_000) String description,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank @Size(max = 64) String timeZone,
        @Valid CalendarRecurrenceRequest recurrence,
        @Size(max = 5) List<@Valid CalendarReminderRequest> reminders) {
}
