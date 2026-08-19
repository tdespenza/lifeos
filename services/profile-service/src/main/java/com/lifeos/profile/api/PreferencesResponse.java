package com.lifeos.profile.api;

import com.lifeos.profile.domain.ProfilePreferences;
import com.lifeos.profile.domain.ProfileTheme;
import com.lifeos.profile.domain.WeekStart;
import java.time.Instant;

/** Public validated preference representation. */
public record PreferencesResponse(
        ProfileTheme theme,
        WeekStart weekStart,
        boolean dailyDigestEnabled,
        int defaultGoalHorizonDays,
        long version,
        Instant updatedAt) {

    public static PreferencesResponse from(ProfilePreferences settings) {
        return new PreferencesResponse(
                settings.getTheme(),
                settings.getWeekStart(),
                settings.isDailyDigestEnabled(),
                settings.getDefaultGoalHorizonDays(),
                settings.getVersion(),
                settings.getUpdatedAt());
    }
}
