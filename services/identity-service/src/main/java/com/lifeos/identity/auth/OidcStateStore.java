package com.lifeos.identity.auth;

import java.time.Duration;
import java.util.Optional;

/**
 * Distributed, single-use OIDC callback-state store.
 */
public interface OidcStateStore {

    /**
     * Stores one state record with a bounded TTL.
     *
     * @param state random callback state
     * @param authorizationState state material
     * @param ttl expiration bound
     */
    void save(String state, OidcAuthorizationState authorizationState, Duration ttl);

    /**
     * Atomically consumes one state record after enforcing any browser transaction binding.
     *
     * @param state random callback state
     * @param browserTransactionHash hash of the browser transaction cookie, or {@code null} for
     *     legacy private-client callbacks
     * @return state record, or empty for expired, reused, or binding-mismatched state
     */
    Optional<OidcAuthorizationState> consume(String state, String browserTransactionHash);

    /**
     * Atomically consumes legacy state without a browser transaction binding.
     *
     * @param state random callback state
     * @return state record, or empty for expired or reused state
     */
    default Optional<OidcAuthorizationState> consume(String state) {
        return consume(state, null);
    }
}
