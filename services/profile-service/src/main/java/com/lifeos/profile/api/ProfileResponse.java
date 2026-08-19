package com.lifeos.profile.api;

import com.lifeos.profile.domain.PersonalProfile;
import java.time.Instant;
import java.util.UUID;

/** Public personal-profile representation; internal tenant identifiers are intentionally omitted. */
public record ProfileResponse(
        UUID id,
        String displayName,
        String locale,
        String timeZone,
        String pronouns,
        String bio,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ProfileResponse from(PersonalProfile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getDisplayName(),
                profile.getLocale(),
                profile.getTimeZone(),
                profile.getPronouns(),
                profile.getBio(),
                profile.getVersion(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
