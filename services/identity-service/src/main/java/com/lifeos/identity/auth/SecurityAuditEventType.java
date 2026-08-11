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
    LOGIN_SESSION_CAPACITY_REACHED,

    /** An OIDC callback completed and created a LifeOS session. */
    OIDC_LOGIN_SUCCEEDED,

    /** An OIDC callback was rejected without exposing provider details. */
    OIDC_CALLBACK_REJECTED,

    /** A dependency required for a safe OIDC decision was unavailable. */
    OIDC_DEPENDENCY_UNAVAILABLE,

    /** An OIDC session could not be created because account capacity was reached. */
    OIDC_SESSION_CAPACITY_REACHED
}
