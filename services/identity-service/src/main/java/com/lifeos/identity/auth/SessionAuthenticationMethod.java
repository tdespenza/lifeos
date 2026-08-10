package com.lifeos.identity.auth;

/**
 * Authentication methods which may establish a LifeOS session.
 */
public enum SessionAuthenticationMethod {

    /** First-party password authentication requires an active password credential. */
    PASSWORD,

    /** OIDC authentication is verified by the provider and does not require a local password. */
    OIDC
}
