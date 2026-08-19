package com.lifeos.gateway.routing;

/**
 * Raised when the gateway's non-waiting Media source-upload admission is full.
 */
public class GatewayMediaUploadCapacityException extends RuntimeException {

    /** Creates a safe capacity rejection without retaining request metadata or content. */
    public GatewayMediaUploadCapacityException() {
        super(null, null, false, false);
    }
}
