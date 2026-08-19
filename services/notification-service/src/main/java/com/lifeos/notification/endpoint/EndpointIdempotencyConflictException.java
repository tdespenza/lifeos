package com.lifeos.notification.endpoint;

/** A retry key was reused for a different encrypted endpoint enrollment payload. */
public class EndpointIdempotencyConflictException extends RuntimeException {

    public EndpointIdempotencyConflictException() {
        super("endpoint idempotency key conflicts with an existing request");
    }
}
