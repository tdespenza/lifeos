package com.lifeos.gateway.ratelimit;

/**
 * Indicates that Redis could not make a safe rate-limit decision. The gateway fails closed rather
 * than falling back to divergent per-JVM counters.
 */
public final class GatewayRateLimitDependencyUnavailableException extends RuntimeException {

    /** Creates a generic dependency-failure classification without exposing Redis details. */
    public GatewayRateLimitDependencyUnavailableException() {
        super(null, null, false, false);
    }

    /**
     * Retains the original failure only as a non-rendered cause for diagnostics.
     *
     * @param cause internal Redis failure
     */
    public GatewayRateLimitDependencyUnavailableException(Throwable cause) {
        super(null, cause, false, false);
    }
}
