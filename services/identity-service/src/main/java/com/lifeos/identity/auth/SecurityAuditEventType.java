package com.lifeos.identity.auth;

/**
 * Redacted security outcomes emitted by the identity service.
 */
public enum SecurityAuditEventType {

    /** A password login created a session. */
    LOGIN_SUCCEEDED,

    /** A credential attempt was rejected without revealing why. */
    LOGIN_FAILED,

    /** A client exceeded the configured login-attempt limit. */
    LOGIN_RATE_LIMITED,

    /** A required authentication dependency was unavailable or timed out. */
    LOGIN_DEPENDENCY_UNAVAILABLE,

    /** A login could not create a session because the account capacity was reached. */
    LOGIN_SESSION_CAPACITY_REACHED
}
