package com.lifeos.identity.auth;

/** Sanitized 400-level failure for malformed session-management query input. */
public class SessionRequestValidationException extends RuntimeException {

    /** Creates a generic validation failure without echoing request data. */
    public SessionRequestValidationException() {
        super("Session request is invalid.");
    }
}
