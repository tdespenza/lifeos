package com.lifeos.gateway.routing;

/**
 * Raised when a request or upstream response exceeds the configured memory bound.
 */
public class GatewayPayloadTooLargeException extends RuntimeException {

    /**
     * Creates a bounded-payload failure that retains a wrapped transport diagnostic.
     *
     * @param cause wrapped overflow discovered by the HTTP client
     */
    public GatewayPayloadTooLargeException(Throwable cause) {
        super(null, cause, false, false);
    }

    /**
     * Creates a bounded-payload failure without echoing request details.
     */
    public GatewayPayloadTooLargeException() {
        super(null, null, false, false);
    }
}
