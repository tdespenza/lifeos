package com.lifeos.gateway.routing;

/**
 * Raised when the gateway's bounded response-buffer admission is full.
 */
public class GatewayResponseBufferCapacityException extends RuntimeException {

    /** Creates a safe capacity rejection without retaining request details. */
    public GatewayResponseBufferCapacityException() {
        super(null, null, false, false);
    }
}
