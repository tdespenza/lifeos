package com.lifeos.profile.idempotency;

/** Raised when a durable reservation cannot safely be read, locked, or completed. */
public class ProfileIdempotencyUnavailableException extends RuntimeException {

    public ProfileIdempotencyUnavailableException() {
        super();
    }

    public ProfileIdempotencyUnavailableException(Throwable cause) {
        super(cause);
    }
}
