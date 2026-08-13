package com.lifeos.identity.auth;

import java.util.UUID;

/**
 * Validated subject context passed only between authenticated internal service boundaries.
 *
 * <p>{@code accessTokenProof} is an opaque proof for the exact access token accepted during
 * validation. It is intentionally not a browser-facing identity claim, audit field, log field,
 * or metric label.
 */
public record AuthenticatedSubject(
        UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

    @Override
    public String toString() {
        return "AuthenticatedSubject[accountId=" + accountId
                + ", sessionId=" + sessionId
                + ", authenticationMethod=" + authenticationMethod
                + ", accessTokenProof=[redacted]]";
    }
}
