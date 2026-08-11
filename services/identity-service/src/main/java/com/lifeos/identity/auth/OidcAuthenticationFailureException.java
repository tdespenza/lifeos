package com.lifeos.identity.auth;

/**
 * Sanitized OIDC failure which intentionally hides provider, subject, and callback details.
 */
public class OidcAuthenticationFailureException extends RuntimeException {

    /**
     * Creates a generic OIDC failure.
     */
    public OidcAuthenticationFailureException() {
        super("The OIDC authentication request could not be completed.");
    }

    /**
     * Creates a generic OIDC failure while retaining the cause for internal diagnostics.
     *
     * @param cause internal failure
     */
    public OidcAuthenticationFailureException(Throwable cause) {
        super("The OIDC authentication request could not be completed.", cause);
    }
}
