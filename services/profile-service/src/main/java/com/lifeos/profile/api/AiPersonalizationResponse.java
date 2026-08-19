package com.lifeos.profile.api;

import com.lifeos.profile.domain.AiContextCategory;
import com.lifeos.profile.domain.ProfileAiPersonalizationSettings;
import java.time.Instant;
import java.util.Set;

/** Public revocable AI-personalization consent and settings representation. */
public record AiPersonalizationResponse(
        boolean consentGranted,
        boolean personalizationEnabled,
        Set<AiContextCategory> allowedContextCategories,
        Instant consentUpdatedAt,
        long version,
        Instant updatedAt) {

    public static AiPersonalizationResponse from(ProfileAiPersonalizationSettings settings) {
        return new AiPersonalizationResponse(
                settings.isConsentGranted(),
                settings.isPersonalizationEnabled(),
                settings.getAllowedContextCategories(),
                settings.getConsentUpdatedAt(),
                settings.getVersion(),
                settings.getUpdatedAt());
    }
}
