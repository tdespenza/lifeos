package com.lifeos.identity.auth;

import java.time.Duration;
import java.util.Optional;

/** Single-use storage boundary for authenticated WebAuthn registration requests. */
public interface WebAuthnRegistrationChallengeStore {

    void save(WebAuthnChallengeId id, WebAuthnRegistrationChallenge challenge, Duration ttl);

    Optional<WebAuthnRegistrationChallenge> consume(WebAuthnChallengeId id);
}
