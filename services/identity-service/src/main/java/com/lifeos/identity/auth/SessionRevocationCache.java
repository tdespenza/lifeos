package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional revocation-only acceleration boundary.
 *
 * <p>The cache is never an authority for an active session. A miss always falls through to the
 * durable session row, which makes Redis restart and eviction safe.
 */
public interface SessionRevocationCache {

    /**
     * Returns a cached revoked decision, or empty when the cache cannot decide.
     *
     * @param sessionId session identifier
     * @return true only for a cached revoked state; empty for a miss or dependency failure
     */
    Optional<Boolean> isRevoked(UUID sessionId);

    /**
     * Publishes a revoked state after the durable transaction commits.
     *
     * @param sessionId revoked session identifier
     * @param expiresAt durable session deadline
     */
    void markRevoked(UUID sessionId, Instant expiresAt);

    /** A no-op implementation used by compatibility constructors and isolated unit tests. */
    SessionRevocationCache NOOP = new SessionRevocationCache() {
        @Override
        public Optional<Boolean> isRevoked(UUID sessionId) {
            return Optional.empty();
        }

        @Override
        public void markRevoked(UUID sessionId, Instant expiresAt) {
            // No cache configured.
        }
    };
}
