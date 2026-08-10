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
}
