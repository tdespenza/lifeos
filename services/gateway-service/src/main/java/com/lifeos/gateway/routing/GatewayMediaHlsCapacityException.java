package com.lifeos.gateway.routing;

/**
 * Raised when the gateway's non-waiting Media HLS response-stream admission is full.
 */
public class GatewayMediaHlsCapacityException extends RuntimeException {

    /** Creates a safe capacity rejection without retaining request metadata or content. */
    public GatewayMediaHlsCapacityException() {
        super(null, null, false, false);
    }
}
