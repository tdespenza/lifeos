package com.lifeos.profile.api;

import com.lifeos.profile.domain.ProfileTheme;
import com.lifeos.profile.domain.WeekStart;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Validated product preferences; free-form keys are deliberately not accepted. */
public record UpdatePreferencesRequest(
        @NotNull ProfileTheme theme,
        @NotNull WeekStart weekStart,
        boolean dailyDigestEnabled,
        @Min(1) @Max(365) int defaultGoalHorizonDays) {
}
