package com.lifeos.profile.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.util.Locale;

/** Validated personal profile creation input. */
public record CreateProfileRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(max = 35) String locale,
        @NotBlank @Size(max = 64) String timeZone,
        @Size(max = 80) String pronouns,
        @Size(max = 1000) String bio) {

    @AssertTrue(message = "locale must be a valid BCP 47 language tag")
    public boolean isLocaleValid() {
        if (locale == null || locale.isBlank()) {
            return false;
        }
        String language = Locale.forLanguageTag(locale.trim()).getLanguage();
        return !language.isBlank() && !"und".equalsIgnoreCase(language);
    }

    @AssertTrue(message = "timeZone must be a valid IANA zone")
    public boolean isTimeZoneValid() {
        if (timeZone == null || timeZone.isBlank()) {
            return false;
        }
        try {
            ZoneId.of(timeZone.trim());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
