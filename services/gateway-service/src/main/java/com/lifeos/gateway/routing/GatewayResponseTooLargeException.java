package com.lifeos.gateway.routing;

/**
 * Raised when an upstream response exceeds the reviewed limit for its explicit relay path.
 */
public class GatewayResponseTooLargeException extends RuntimeException {

    /** Creates an upstream-bound violation without retaining response content. */
    public GatewayResponseTooLargeException() {
        super(null, null, false, false);
    }
}
