package com.lifeos.identity.auth;

/**
 * Sanitized authentication failure that intentionally does not identify the failed credential
 * branch.
 */
public class AuthenticationFailureException extends RuntimeException {

    /**
     * Creates the generic authentication failure.
     */
    public AuthenticationFailureException() {
        super("The supplied credentials could not be verified.");
    }

    /**
     * Creates a generic authentication failure while retaining the internal diagnostic cause.
     *
     * @param cause internal failure cause; never exposed in the HTTP response
     */
    public AuthenticationFailureException(Throwable cause) {
        super("The supplied credentials could not be verified.", cause);
    }
}
