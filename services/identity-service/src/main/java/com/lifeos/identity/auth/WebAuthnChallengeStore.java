package com.lifeos.identity.auth;

import com.yubico.webauthn.AssertionRequest;
import java.time.Duration;
import java.util.Optional;

/**
 * Distributed, single-use WebAuthn assertion-request store.
 */
public interface WebAuthnChallengeStore {

    /**
     * Stores one server-generated assertion request with a bounded TTL.
     *
     * @param challengeId opaque client correlation value
     * @param request immutable WebAuthn request containing the random challenge
     * @param ttl expiration bound
     */
    void save(String challengeId, AssertionRequest request, Duration ttl);

    /**
     * Atomically consumes one assertion request.
     *
     * @param challengeId opaque client correlation value
     * @return request, or empty for stale, malformed, or replayed state
     */
    Optional<AssertionRequest> consume(String challengeId);
}
