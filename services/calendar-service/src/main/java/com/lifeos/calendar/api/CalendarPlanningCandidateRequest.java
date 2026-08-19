package com.lifeos.calendar.api;

import com.lifeos.calendar.domain.CalendarLinkType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A bounded owner-authenticated Task/Goal that may receive a suggested focus interval. */
public record CalendarPlanningCandidateRequest(
        @NotNull CalendarLinkType linkType,
        @NotNull UUID resourceId,
        @Min(15) @Max(480) int focusMinutes) {}
