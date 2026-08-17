package com.lifeos.gateway.routing;

import org.springframework.http.HttpStatus;

/**
 * Safe, client-facing classification for an upstream failure.
 */
public class GatewayUpstreamException extends RuntimeException {

    private final HttpStatus status;
    private final String failureClass;
    private final int retryAfterSeconds;

    /**
     * Creates an upstream failure without retaining sensitive downstream details in the message.
     *
     * @param status safe HTTP status to return
     * @param cause original failure for logging/diagnostics
     */
    public GatewayUpstreamException(HttpStatus status, Throwable cause) {
        this(status, cause, "upstream", 0);
    }

    private GatewayUpstreamException(
            HttpStatus status, Throwable cause, String failureClass, int retryAfterSeconds) {
        super(null, cause, false, false);
        this.status = status;
        this.failureClass = failureClass;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Creates a generic 503 degradation classification for circuit or bulkhead admission. */
    public static GatewayUpstreamException serviceUnavailable(
            String failureClass, int retryAfterSeconds) {
        return new GatewayUpstreamException(
                HttpStatus.SERVICE_UNAVAILABLE, null, failureClass, retryAfterSeconds);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
