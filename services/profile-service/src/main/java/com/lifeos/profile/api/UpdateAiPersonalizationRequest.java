package com.lifeos.profile.api;

import com.lifeos.profile.domain.AiContextCategory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/** Explicit, revocable AI-personalization consent input. */
public record UpdateAiPersonalizationRequest(
        boolean consentGranted,
        boolean personalizationEnabled,
        @NotNull Set<AiContextCategory> allowedContextCategories) {

    @AssertTrue(message = "AI categories require active consent and personalization")
    public boolean isConsentStateConsistent() {
        return (consentGranted && personalizationEnabled) || allowedContextCategories == null || allowedContextCategories.isEmpty();
    }
}
