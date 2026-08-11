package com.lifeos.identity.auth;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Browser-facing passkey request options plus the opaque server-side challenge handle.
 *
 * @param challengeId single-use server challenge handle
 * @param publicKey WebAuthn {@code publicKey} request options for {@code navigator.credentials.get}
 */
public record PasskeyAuthenticationOptions(String challengeId, JsonNode publicKey) {
}
