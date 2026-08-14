package com.lifeos.gateway.routing;

/**
 * Raised when a request or upstream response exceeds the configured memory bound.
 */
public class GatewayPayloadTooLargeException extends RuntimeException {

    /**
     * Creates a bounded-payload failure without echoing request details.
     */
    public GatewayPayloadTooLargeException() {
        super(null, null, false, false);
    }
}
