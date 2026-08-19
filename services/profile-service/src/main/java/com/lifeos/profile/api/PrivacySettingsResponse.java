package com.lifeos.profile.api;

import com.lifeos.profile.domain.ProfilePrivacySettings;
import com.lifeos.profile.domain.ProfileVisibility;
import java.time.Instant;

/** Public privacy-settings representation. */
public record PrivacySettingsResponse(
        ProfileVisibility profileVisibility,
        boolean shareActivityWithHousehold,
        boolean allowHouseholdDirectory,
        long version,
        Instant updatedAt) {

    public static PrivacySettingsResponse from(ProfilePrivacySettings settings) {
        return new PrivacySettingsResponse(
                settings.getProfileVisibility(),
                settings.isShareActivityWithHousehold(),
                settings.isAllowHouseholdDirectory(),
                settings.getVersion(),
                settings.getUpdatedAt());
    }
}
