package com.lifeos.identity.auth;

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
    void save(String state, OidcAuthorizationState authorizationState, java.time.Duration ttl);

    /**
     * Atomically consumes one state record.
     *
     * @param state random callback state
     * @return state record, or empty for expired/reused state
     */
    java.util.Optional<OidcAuthorizationState> consume(String state);
}
