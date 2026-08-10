package com.lifeos.identity.auth;

/**
 * Indicates that a dependency required to make a safe authentication decision failed.
 */
public class AuthenticationDependencyUnavailableException extends RuntimeException {

    /**
     * Creates a sanitized dependency-failure exception.
     */
    public AuthenticationDependencyUnavailableException() {
        super("Authentication is temporarily unavailable.");
    }

    /**
     * Creates a sanitized dependency-failure exception with an internal cause.
     *
     * @param cause dependency failure
     */
    public AuthenticationDependencyUnavailableException(Throwable cause) {
        super("Authentication is temporarily unavailable.", cause);
    }
}
