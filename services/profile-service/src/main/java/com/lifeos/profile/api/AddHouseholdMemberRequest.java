package com.lifeos.profile.api;

import com.lifeos.profile.domain.FamilyRelationshipType;
import com.lifeos.profile.domain.HouseholdPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

/** Adds one opaque account reference with an explicit relationship and finite scope grants. */
public record AddHouseholdMemberRequest(
        @NotNull UUID accountId,
        @NotNull FamilyRelationshipType relationshipType,
        @NotEmpty Set<HouseholdPermission> permissions) {
}
