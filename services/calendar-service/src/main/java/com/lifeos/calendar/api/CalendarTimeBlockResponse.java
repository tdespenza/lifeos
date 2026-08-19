package com.lifeos.calendar.api;

import com.lifeos.calendar.domain.CalendarLinkType;
import com.lifeos.calendar.domain.CalendarTimeBlock;
import com.lifeos.calendar.domain.CalendarTimeBlockStatus;
import java.time.Instant;
import java.util.UUID;

/** Versioned time-block representation. */
public record CalendarTimeBlockResponse(
        UUID id,
        CalendarLinkType linkType,
        UUID linkedResourceId,
        Instant startAt,
        Instant endAt,
        String timeZone,
        CalendarTimeBlockStatus status,
        long version) {

    public static CalendarTimeBlockResponse from(CalendarTimeBlock block) {
        return new CalendarTimeBlockResponse(
                block.getId(),
                block.getLinkType(),
                block.getLinkedResourceId(),
                block.getStartAt(),
                block.getEndAt(),
                block.getTimeZone(),
                block.getStatus(),
                block.getVersion());
    }
}
