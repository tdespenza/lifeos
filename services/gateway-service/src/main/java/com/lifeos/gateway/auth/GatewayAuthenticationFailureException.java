package com.lifeos.gateway.auth;

/**
 * Raised when a bearer credential is absent or fails identity validation.
 */
public class GatewayAuthenticationFailureException extends RuntimeException {

    /**
     * Creates a client-safe authentication failure without retaining credential text.
     */
    public GatewayAuthenticationFailureException() {
        super(null, null, false, false);
    }

    /**
     * Creates a client-safe authentication failure while retaining a non-client-visible cause.
     *
     * @param cause internal failure cause
     */
    public GatewayAuthenticationFailureException(Throwable cause) {
        super(null, cause, false, false);
    }
}
