package com.lifeos.calendar.service;

import java.time.Instant;
import java.util.UUID;

/** Privacy-minimized conflicting interval. No title, description, location, or attendee is exposed. */
public record CalendarConflict(String sourceType, UUID sourceId, Instant startAt, Instant endAt, String timeZone) {
}
