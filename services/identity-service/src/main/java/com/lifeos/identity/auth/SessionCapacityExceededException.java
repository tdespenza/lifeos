package com.lifeos.identity.auth;

/**
 * Indicates that an account reached the configured active-session capacity.
 */
public class SessionCapacityExceededException extends RuntimeException {

    /**
     * Creates a sanitized session-capacity failure.
     */
    public SessionCapacityExceededException() {
        super("The account has reached its active session capacity.");
    }
}
