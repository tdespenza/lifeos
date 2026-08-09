package com.lifeos.identity.auth;

/**
 * Lifecycle state of a first-party password credential.
 */
public enum PasswordCredentialStatus {

    /** Credential may be used for password verification. */
    ACTIVE,

    /** Credential is retained but cannot authenticate. */
    DISABLED,

    /** Credential is permanently invalid and must not be reused. */
    REVOKED
}
