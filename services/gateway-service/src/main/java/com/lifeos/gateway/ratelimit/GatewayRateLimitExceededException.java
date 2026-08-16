package com.lifeos.gateway.ratelimit;

/**
 * Indicates that a client exhausted the configured route budget.
 *
 * <p>The exception contains only bounded configuration values. It never retains the Redis key,
 * account identifier, address, or request credential.
 */
public final class GatewayRateLimitExceededException extends RuntimeException {

    private final int limit;
    private final int retryAfterSeconds;

    /**
     * Creates a generic rate-limit rejection.
     *
     * @param limit configured requests per window
     * @param retryAfterSeconds safe upper-bound retry delay
     */
    public GatewayRateLimitExceededException(int limit, int retryAfterSeconds) {
        super("The request rate limit has been exceeded.", null, false, false);
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getLimit() {
        return limit;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
