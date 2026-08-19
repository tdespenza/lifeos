package com.lifeos.profile.api;

import com.lifeos.profile.domain.Household;
import java.time.Instant;
import java.util.UUID;

/** Scoped household representation available only after an explicit local permission check. */
public record HouseholdResponse(UUID id, String name, long version, Instant createdAt, Instant updatedAt) {

    public static HouseholdResponse from(Household household) {
        return new HouseholdResponse(
                household.getId(),
                household.getName(),
                household.getVersion(),
                household.getCreatedAt(),
                household.getUpdatedAt());
    }
}
