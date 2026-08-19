package com.lifeos.identity.authorization;

import java.util.Optional;

/** Loads the active, versioned authorization policy without domain-resource coupling. */
public interface AuthorizationPolicyRepository {

    /**
     * Returns the one active policy snapshot.
     *
     * @return immutable active policy
     * @throws RuntimeException when the policy authority cannot safely load a policy
     */
    AuthorizationPolicy loadCurrentPolicy();

    /**
     * Resolves a bounded compatibility snapshot when a protected service has not yet moved to the
     * active policy version.
     *
     * <p>The default is intentionally empty. A policy source must opt in to every compatible
     * version and action; callers can never receive an implicit downgrade or a wildcard policy.
     *
     * @param expectedPolicyVersion exact version understood by the protected service
     * @param action exact closed action requested by that service
     * @return an immutable compatible policy snapshot, or empty when no safe compatibility view
     *     exists
     */
    default Optional<AuthorizationPolicy> findCompatiblePolicy(
            String expectedPolicyVersion, AuthorizationAction action) {
        return Optional.empty();
    }
}
