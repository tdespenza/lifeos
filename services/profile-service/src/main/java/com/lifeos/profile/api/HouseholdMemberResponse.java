package com.lifeos.profile.api;

import com.lifeos.profile.domain.FamilyRelationshipType;
import com.lifeos.profile.domain.HouseholdMember;
import com.lifeos.profile.domain.HouseholdPermission;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Account identifier plus relationship and permissions visible only in a household member scope. */
public record HouseholdMemberResponse(
        UUID accountId,
        FamilyRelationshipType relationshipType,
        Set<HouseholdPermission> permissions,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static HouseholdMemberResponse from(HouseholdMember member) {
        return new HouseholdMemberResponse(
                member.getMemberAccountId(),
                member.getRelationshipType(),
                member.getPermissions(),
                member.getVersion(),
                member.getCreatedAt(),
                member.getUpdatedAt());
    }
}
