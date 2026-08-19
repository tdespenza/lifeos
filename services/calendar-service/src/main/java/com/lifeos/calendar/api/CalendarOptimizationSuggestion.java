package com.lifeos.calendar.api;

import java.time.Instant;

/** Explainable suggestion generated only from Calendar's owner-scoped schedule. */
public record CalendarOptimizationSuggestion(String reason, Instant startAt, Instant endAt, String timeZone) {
}
