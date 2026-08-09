package com.lifeos.identity.auth;

/**
 * Indicates that a login client exceeded the bounded distributed attempt limit.
 */
public class LoginRateLimitExceededException extends RuntimeException {

    /** Lower-bound retry delay communicated to the client. */
    private final long retryAfterSeconds;

    /**
     * Creates a rate-limit exception without exposing the account or limiter key.
     *
     * @param retryAfterSeconds lower-bound retry delay
     */
    public LoginRateLimitExceededException(long retryAfterSeconds) {
        super("Authentication attempts are temporarily limited.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the retry delay communicated through the standard response header.
     *
     * @return retry delay in seconds
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
