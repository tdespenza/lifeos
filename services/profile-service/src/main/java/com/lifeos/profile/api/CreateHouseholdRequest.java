package com.lifeos.profile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Name for a new explicitly scoped household or family relationship space. */
public record CreateHouseholdRequest(@NotBlank @Size(max = 120) String name) {
}
