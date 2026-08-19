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
    OIDC_SESSION_CAPACITY_REACHED,

    /** A passkey assertion completed and created a LifeOS session. */
    PASSKEY_LOGIN_SUCCEEDED,

    /** A passkey assertion was rejected without exposing protocol details. */
    PASSKEY_ASSERTION_REJECTED,

    /** A passkey client exceeded the bounded distributed attempt limit. */
    PASSKEY_LOGIN_RATE_LIMITED,

    /** A dependency required for a safe passkey decision was unavailable. */
    PASSKEY_DEPENDENCY_UNAVAILABLE,

    /** A passkey session could not be created because account capacity was reached. */
    PASSKEY_SESSION_CAPACITY_REACHED,

    /** An authenticated caller started a passkey registration ceremony. */
    PASSKEY_REGISTRATION_STARTED,

    /** An authenticated caller registered a new passkey credential. */
    PASSKEY_REGISTRATION_SUCCEEDED,

    /** A passkey registration was rejected without exposing protocol details. */
    PASSKEY_REGISTRATION_REJECTED,

    /** An authenticated caller disabled one of their passkey credentials. */
    PASSKEY_CREDENTIAL_REVOKED,

    /** A passkey credential-management request was rejected without exposing ownership details. */
    PASSKEY_CREDENTIAL_REVOCATION_REJECTED,

    /** An authenticated caller generated a replacement set of one-time passkey recovery codes. */
    PASSKEY_RECOVERY_CODES_ISSUED,

    /** A one-time passkey recovery code created a new session. */
    PASSKEY_RECOVERY_SUCCEEDED,

    /** A passkey recovery code or account lookup was rejected without exposing account state. */
    PASSKEY_RECOVERY_REJECTED,

    /** A dependency required for safe passkey recovery was unavailable. */
    PASSKEY_RECOVERY_DEPENDENCY_UNAVAILABLE,

    /** A client exceeded the bounded passkey-recovery attempt limit. */
    PASSKEY_RECOVERY_RATE_LIMITED,

    /** A trusted workload received an authorization allow decision. */
    AUTHORIZATION_ALLOWED,

    /** A trusted workload received a deterministic authorization denial. */
    AUTHORIZATION_DENIED,

    /** A required authorization dependency could not complete safely. */
    AUTHORIZATION_DEPENDENCY_UNAVAILABLE,

    /** A user requested a single-session or bulk session revocation outcome. */
    SESSION_REVOKED,

    /** A public first-party registration atomically created an account and password credential. */
    ACCOUNT_REGISTRATION_SUCCEEDED,

    /** A matching retry returned an already completed public registration result. */
    ACCOUNT_REGISTRATION_REPLAYED,

    /** A public registration was rejected without persisting request secrets or personal data. */
    ACCOUNT_REGISTRATION_REJECTED,

    /** A dependency required to safely process public registration was unavailable. */
    ACCOUNT_REGISTRATION_DEPENDENCY_UNAVAILABLE,

    /** An authenticated caller attempted to read an account other than their own. */
    ACCOUNT_READ_DENIED
}
