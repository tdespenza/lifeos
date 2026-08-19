package com.lifeos.profile.api;

import com.lifeos.profile.domain.ProfileVisibility;
import jakarta.validation.constraints.NotNull;

/** Self-owned privacy settings. */
public record UpdatePrivacySettingsRequest(
        @NotNull ProfileVisibility profileVisibility,
        boolean shareActivityWithHousehold,
        boolean allowHouseholdDirectory) {
}
