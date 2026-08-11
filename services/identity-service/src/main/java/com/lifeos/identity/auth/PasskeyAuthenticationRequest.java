package com.lifeos.identity.auth;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Browser assertion completion request.
 *
 * @param challengeId opaque server-side challenge handle
 * @param credential JSON-encoded {@code PublicKeyCredential} returned by the browser
 */
public record PasskeyAuthenticationRequest(
        @NotBlank @Size(max = 64) String challengeId,
        @NotNull JsonNode credential) {
}
