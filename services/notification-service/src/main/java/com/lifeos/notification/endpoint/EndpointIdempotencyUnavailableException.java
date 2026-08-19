package com.lifeos.notification.endpoint;

/** A winning endpoint enrollment transaction has not completed safely yet. */
public class EndpointIdempotencyUnavailableException extends RuntimeException {

    public EndpointIdempotencyUnavailableException() {
        super("endpoint idempotency request is temporarily unavailable");
    }
}
