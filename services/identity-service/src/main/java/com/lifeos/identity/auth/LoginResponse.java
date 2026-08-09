package com.lifeos.identity.auth;

import java.util.UUID;

/**
 * Versioned first-party login result.
 *
 * <p>The access token is signed by the identity service and is never persisted in raw form. Refresh
 * token rotation remains a follow-up Story 1.5 concern.
 *
 * @param sessionId durable session identifier
 * @param accessToken signed short-lived access token
 * @param tokenType token type used by the Authorization header
 * @param expiresIn access-token lifetime in seconds
 */
public record LoginResponse(
        UUID sessionId,
        String accessToken,
        String tokenType,
        long expiresIn) {
}
