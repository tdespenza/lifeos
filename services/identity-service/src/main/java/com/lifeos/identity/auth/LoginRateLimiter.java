package com.lifeos.identity.auth;

/**
 * Distributed login-attempt limiter abstraction.
 */
public interface LoginRateLimiter {

    /**
     * Checks and records one login attempt for a privacy-preserving user/client key.
     *
     * @param normalizedEmail canonical email used only to derive the limiter key
     * @param clientAddress request source address used only to derive the limiter key
     * @throws LoginRateLimitExceededException when the bounded threshold is exceeded
     * @throws AuthenticationDependencyUnavailableException when the limiter cannot fail safely
     */
    void check(String normalizedEmail, String clientAddress);
}
