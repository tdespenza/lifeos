package com.lifeos.calendar.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Bounded recurrence policy; single-occurrence/split-series edits remain intentionally unsupported. */
public record CalendarRecurrenceRequest(
        @NotNull CalendarRecurrenceFrequency frequency,
        @Min(1) @Max(365) int interval,
        @NotNull @Min(1) @Max(1_000) Integer count) {
}
