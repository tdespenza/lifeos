package com.lifeos.identity.auth;

import com.fasterxml.jackson.databind.JsonNode;

/** Browser-facing WebAuthn creation options and opaque single-use challenge handle. */
public record PasskeyRegistrationOptions(String challengeId, JsonNode publicKey) {
}
