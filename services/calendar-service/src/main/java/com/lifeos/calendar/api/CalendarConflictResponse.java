package com.lifeos.calendar.api;

import com.lifeos.calendar.service.CalendarConflict;
import java.time.Instant;
import java.util.UUID;

/** Privacy-minimized conflict representation. */
public record CalendarConflictResponse(String sourceType, UUID sourceId, Instant startAt, Instant endAt, String timeZone) {

    public static CalendarConflictResponse from(CalendarConflict conflict) {
        return new CalendarConflictResponse(
                conflict.sourceType(), conflict.sourceId(), conflict.startAt(), conflict.endAt(), conflict.timeZone());
    }
}
