package com.lifeos.gateway.ratelimit;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.routing.GatewayRoute;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Gateway request admission policy. Implementations must make a distributed decision before the
 * request reaches an upstream and must not use raw client identifiers as metric labels.
 */
@FunctionalInterface
public interface GatewayRateLimiter {

    /**
     * Charges one request to the route and its authenticated or anonymous client identity.
     *
     * @param route resolved gateway route
     * @param request inbound request
     * @param subject validated account subject, or {@code null} for an anonymous request
     * @throws GatewayRateLimitExceededException when the client budget is exhausted
     * @throws GatewayRateLimitDependencyUnavailableException when the distributed decision cannot
     *     be made safely
     */
    void check(GatewayRoute route, HttpServletRequest request, GatewayAuthenticatedSubject subject);

    /**
     * Creates an allow-all policy for source-compatible isolated controller tests. Production
     * wiring always supplies {@link RedisGatewayRateLimiter}.
     *
     * @return no-op limiter
     */
    static GatewayRateLimiter allowAll() {
        return (route, request, subject) -> {
            // Deliberately empty: this is only used by legacy isolated forwarding fixtures.
        };
    }
}
