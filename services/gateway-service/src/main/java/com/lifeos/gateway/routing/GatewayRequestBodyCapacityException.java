package com.lifeos.gateway.routing;

/**
 * Raised when the gateway's bounded request-body buffering admission is full.
 */
public class GatewayRequestBodyCapacityException extends RuntimeException {

    /** Creates a safe capacity rejection without retaining request details. */
    public GatewayRequestBodyCapacityException() {
        super(null, null, false, false);
    }
}
