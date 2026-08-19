package com.lifeos.calendar.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** Bounded local planning request; it never asks Calendar to mutate a schedule. */
public record CalendarOptimizationRequest(
        @NotNull Instant from,
        @NotNull Instant to,
        @Min(15) @Max(480) int minimumFocusMinutes,
        @Min(1) @Max(50) int maxSuggestions,
        @jakarta.validation.constraints.Size(max = 50)
                List<@jakarta.validation.Valid CalendarPlanningCandidateRequest> candidates) {

    public CalendarOptimizationRequest(Instant from, Instant to, int minimumFocusMinutes, int maxSuggestions) {
        this(from, to, minimumFocusMinutes, maxSuggestions, List.of());
    }
}
