package com.lifeos.gateway.routing;

/**
 * Raised when the inbound request target cannot be represented as a safe upstream URI.
 */
public class GatewayBadRequestException extends RuntimeException {

    /**
     * Creates a client-safe invalid-target failure without echoing request data.
     */
    public GatewayBadRequestException() {
        super(null, null, false, false);
    }
}
