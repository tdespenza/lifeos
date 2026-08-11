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
     * Atomically consumes one state record.
     *
     * @param state random callback state
     * @return state record, or empty for expired/reused state
     */
    Optional<OidcAuthorizationState> consume(String state);
}
