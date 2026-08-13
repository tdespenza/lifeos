package com.lifeos.identity.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque, deterministic cursor for the indexed session ordering. */
record SessionPageCursor(Instant lastUsedAt, Instant createdAt, UUID sessionId) {

    private static final String SEPARATOR = "|";

    String encode() {
        String value = lastUsedAt + SEPARATOR + createdAt + SEPARATOR + sessionId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static SessionPageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 512) {
            throw new SessionRequestValidationException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3) {
                throw new SessionRequestValidationException();
            }
            return new SessionPageCursor(
                    Instant.parse(parts[0]), Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException exception) {
            throw new SessionRequestValidationException();
        }
    }
}
