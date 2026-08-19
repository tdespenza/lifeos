package com.lifeos.calendar.api;

import com.lifeos.calendar.domain.CalendarLinkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Input for a focus block or a future Task/Goal-linked block. */
public record CreateCalendarTimeBlockRequest(
        @NotNull CalendarLinkType linkType,
        UUID linkedResourceId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank @Size(max = 64) String timeZone) {
}
