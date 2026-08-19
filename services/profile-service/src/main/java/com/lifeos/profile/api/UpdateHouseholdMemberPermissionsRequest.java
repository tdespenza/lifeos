package com.lifeos.profile.api;

import com.lifeos.profile.domain.HouseholdPermission;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/** Replaces an existing household member's explicit finite permission set. */
public record UpdateHouseholdMemberPermissionsRequest(@NotEmpty Set<HouseholdPermission> permissions) {
}
