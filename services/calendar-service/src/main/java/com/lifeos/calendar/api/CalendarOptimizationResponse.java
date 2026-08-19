package com.lifeos.calendar.api;

import java.util.List;

/** Stable result that reports missing planning dependencies rather than inventing recommendations. */
public record CalendarOptimizationResponse(List<CalendarOptimizationSuggestion> suggestions, List<String> degradedSources) {
}
