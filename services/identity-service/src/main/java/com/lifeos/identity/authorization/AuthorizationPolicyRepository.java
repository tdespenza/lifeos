package com.lifeos.identity.authorization;

/** Loads the active, versioned authorization policy without domain-resource coupling. */
public interface AuthorizationPolicyRepository {

    /**
     * Returns the one active policy snapshot.
     *
     * @return immutable active policy
     * @throws RuntimeException when the policy authority cannot safely load a policy
     */
    AuthorizationPolicy loadCurrentPolicy();
}
