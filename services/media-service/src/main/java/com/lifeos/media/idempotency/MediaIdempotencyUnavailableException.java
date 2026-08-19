package com.lifeos.media.idempotency;

/** Durable retry state could not be safely read or committed. */
public class MediaIdempotencyUnavailableException extends RuntimeException {

    public MediaIdempotencyUnavailableException() {
    }

    public MediaIdempotencyUnavailableException(Throwable cause) {
        super(cause);
    }
}
