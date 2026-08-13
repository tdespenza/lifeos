package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Non-secret session projection returned to the owning account only.
 *
 * @param sessionId stable session identifier
 * @param deviceLabel bounded display label
 * @param platform coarse platform family
 * @param browserFamily coarse browser family
 * @param coarseLocation coarse location label
 * @param authenticationMethod factor that established the session
 * @param createdAt session creation timestamp
 * @param lastUsedAt most recent recorded successful use
 * @param expiresAt durable session deadline
 * @param current whether this is the authenticated request's session
 * @param revoked monotonic durable revocation state
 */
public record SessionSummary(
        UUID sessionId,
        String deviceLabel,
        String platform,
        String browserFamily,
        String coarseLocation,
        String authenticationMethod,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        boolean current,
        boolean revoked) {

    /**
     * Projects a durable session without exposing token digests or other security material.
     *
     * @param session durable session
     * @param currentSessionId authenticated session identifier
     * @return safe response projection
     */
    public static SessionSummary from(AuthSession session, UUID currentSessionId) {
        return new SessionSummary(
                session.getId(),
                session.getDeviceLabel(),
                session.getPlatform(),
                session.getBrowserFamily(),
                session.getCoarseLocation(),
                session.getAuthenticationMethod().name(),
                session.getCreatedAt(),
                session.getLastUsedAt(),
                session.getExpiresAt(),
                session.getId().equals(currentSessionId),
                session.isRevoked());
    }
}
