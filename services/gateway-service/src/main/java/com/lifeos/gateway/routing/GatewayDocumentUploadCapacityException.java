package com.lifeos.gateway.routing;

/**
 * Raised when the gateway's non-waiting Document Vault request-streaming admission is full.
 */
public class GatewayDocumentUploadCapacityException extends RuntimeException {

    /** Creates a safe capacity rejection without retaining request metadata or content. */
    public GatewayDocumentUploadCapacityException() {
        super(null, null, false, false);
    }
}
