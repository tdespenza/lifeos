package com.lifeos.calendar.api;

import com.lifeos.calendar.domain.CalendarLinkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Replacement input for a time block under strong optimistic concurrency. */
public record UpdateCalendarTimeBlockRequest(
        @NotNull CalendarLinkType linkType,
        UUID linkedResourceId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank @Size(max = 64) String timeZone) {
}
