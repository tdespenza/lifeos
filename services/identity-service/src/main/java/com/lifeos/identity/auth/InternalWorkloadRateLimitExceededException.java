package com.lifeos.identity.auth;

/** Raised when an authenticated workload exceeds its bounded internal request budget. */
public class InternalWorkloadRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * Creates a generic, non-sensitive workload rate-limit outcome.
     *
     * @param retryAfterSeconds bounded retry delay
     */
    public InternalWorkloadRateLimitExceededException(long retryAfterSeconds) {
        super("Internal authorization requests are temporarily limited.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** @return seconds a caller should wait before retrying */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
