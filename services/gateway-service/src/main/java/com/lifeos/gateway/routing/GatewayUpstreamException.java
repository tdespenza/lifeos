package com.lifeos.gateway.routing;

import org.springframework.http.HttpStatus;

/**
 * Safe, client-facing classification for an upstream failure.
 */
public class GatewayUpstreamException extends RuntimeException {

    private final HttpStatus status;

    /**
     * Creates an upstream failure without retaining sensitive downstream details in the message.
     *
     * @param status safe HTTP status to return
     * @param cause original failure for logging/diagnostics
     */
    public GatewayUpstreamException(HttpStatus status, Throwable cause) {
        super(null, cause, false, false);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
