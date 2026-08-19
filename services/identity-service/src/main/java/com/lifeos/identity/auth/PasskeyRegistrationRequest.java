package com.lifeos.identity.auth;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Authenticated browser registration completion envelope. */
public record PasskeyRegistrationRequest(
        @NotBlank @Size(max = 64) String challengeId,
        @NotNull JsonNode credential) {
}
