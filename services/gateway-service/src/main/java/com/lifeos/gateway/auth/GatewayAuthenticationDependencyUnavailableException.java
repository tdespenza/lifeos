package com.lifeos.gateway.auth;

/**
 * Raised when the identity authority cannot safely complete a protected-request validation.
 */
public class GatewayAuthenticationDependencyUnavailableException extends RuntimeException {

    /**
     * Creates a client-safe dependency failure without exposing topology or exception text.
     */
    public GatewayAuthenticationDependencyUnavailableException() {
        super(null, null, false, false);
    }

    /**
     * Creates a client-safe dependency failure while retaining a non-client-visible cause.
     *
     * @param cause internal failure cause
     */
    public GatewayAuthenticationDependencyUnavailableException(Throwable cause) {
        super(null, cause, false, false);
    }
}
