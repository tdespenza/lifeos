package com.lifeos.notification.endpoint;

/** Endpoint enrollment requests must carry one bounded opaque retry key. */
public class InvalidEndpointIdempotencyKeyException extends RuntimeException {

    public InvalidEndpointIdempotencyKeyException() {
        super("a valid Idempotency-Key header is required");
    }
}
