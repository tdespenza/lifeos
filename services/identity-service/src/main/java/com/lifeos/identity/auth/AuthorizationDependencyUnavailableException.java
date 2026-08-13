package com.lifeos.identity.auth;

/**
 * Raised when a required authorization dependency cannot complete safely.
 *
 * <p>Authorization fails closed: callers receive no allow decision when its required audit or
 * policy boundary is unavailable.
 */
public class AuthorizationDependencyUnavailableException extends RuntimeException {

    /**
     * Creates a sanitized dependency failure.
     */
    public AuthorizationDependencyUnavailableException() {
        super("Authorization is temporarily unavailable.");
    }

    /**
     * Creates a sanitized dependency failure while preserving the cause for server diagnostics.
     *
     * @param cause underlying dependency exception
     */
    public AuthorizationDependencyUnavailableException(Throwable cause) {
        super("Authorization is temporarily unavailable.", cause);
    }
}
