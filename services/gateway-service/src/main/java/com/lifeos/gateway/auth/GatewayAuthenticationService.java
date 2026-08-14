package com.lifeos.gateway.auth;

import com.lifeos.gateway.observability.RequestContext;
import com.lifeos.gateway.routing.GatewayRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinates protected-route validation, redacted security metrics, and safe diagnostics.
 */
@Component
public class GatewayAuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayAuthenticationService.class);

    private final GatewayAuthenticationClient client;
    private final GatewayAuthenticationMetrics metrics;

    /**
     * Creates the gateway authentication boundary.
     *
     * @param client identity-service validation client
     * @param metrics redacted security metric publisher
     */
    public GatewayAuthenticationService(
            GatewayAuthenticationClient client, GatewayAuthenticationMetrics metrics) {
        this.client = client;
        this.metrics = metrics;
    }

    /**
     * Validates a request for one configured protected route.
     *
     * @param route finite configured route
     * @param authorizationHeader inbound authorization header
     * @return sanitized authenticated subject
     */
    public GatewayAuthenticatedSubject authenticate(GatewayRoute route, String authorizationHeader) {
        try {
            return client.authenticate(authorizationHeader);
        } catch (GatewayAuthenticationFailureException exception) {
            metrics.recordRejection(route.id(), "invalid_credentials");
            logRejection(route, "invalid_credentials");
            throw exception;
        } catch (GatewayAuthenticationDependencyUnavailableException exception) {
            metrics.recordRejection(route.id(), "identity_unavailable");
            logRejection(route, "identity_unavailable");
            throw exception;
        }
    }

    private static void logRejection(GatewayRoute route, String reason) {
        LOGGER.warn(
                "gateway authentication rejected routeId={} reason={} correlationId={}",
                route.id(),
                reason,
                RequestContext.CORRELATION_ID.isBound() ? RequestContext.CORRELATION_ID.get() : "unbound");
    }
}
