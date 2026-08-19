package com.lifeos.gateway.routing;

/**
 * Raised when the gateway's non-waiting live-SSE connection admission is full.
 */
public class GatewayStreamingCapacityException extends RuntimeException {

    /** Creates a safe capacity rejection without retaining request details. */
    public GatewayStreamingCapacityException() {
        super(null, null, false, false);
    }
}
