package com.lifeos.gateway.auth;

import com.lifeos.gateway.config.GatewayAuthenticationProperties;
import com.lifeos.gateway.observability.RequestContext;
import com.lifeos.gateway.routing.GatewayRoute;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Coordinates protected-route validation, redacted security metrics, and safe diagnostics.
 */
@Component
public class GatewayAuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayAuthenticationService.class);
    private static final int DEFAULT_MAX_CONCURRENT_VALIDATIONS = 64;

    private final GatewayAuthenticationClient client;
    private final GatewayAuthenticationMetrics metrics;
    private final Semaphore validationBulkhead;

    /**
     * Creates the gateway authentication boundary.
     *
     * @param client identity-service validation client
     * @param metrics redacted security metric publisher
     */
    @Autowired
    public GatewayAuthenticationService(
            GatewayAuthenticationClient client,
            GatewayAuthenticationMetrics metrics,
            GatewayAuthenticationProperties properties) {
        this(client, metrics, properties.getMaxConcurrentValidations());
    }

    /**
     * Creates a service with the default validation bulkhead for isolated tests and local callers.
     *
     * @param client identity-service validation client
     * @param metrics redacted security metric publisher
     */
    public GatewayAuthenticationService(
            GatewayAuthenticationClient client, GatewayAuthenticationMetrics metrics) {
        this(client, metrics, DEFAULT_MAX_CONCURRENT_VALIDATIONS);
    }

    GatewayAuthenticationService(
            GatewayAuthenticationClient client, GatewayAuthenticationMetrics metrics, int maxConcurrentValidations) {
        if (maxConcurrentValidations < 1) {
            throw new IllegalArgumentException("maxConcurrentValidations must be positive");
        }
        this.client = client;
        this.metrics = metrics;
        this.validationBulkhead = new Semaphore(maxConcurrentValidations, true);
    }

    /**
     * Validates a request for one configured protected route.
     *
     * @param route finite configured route
     * @param authorizationHeader inbound authorization header
     * @return sanitized authenticated subject
     */
    public GatewayAuthenticatedSubject authenticate(GatewayRoute route, String authorizationHeader) {
        if (!validationBulkhead.tryAcquire()) {
            GatewayAuthenticationDependencyUnavailableException exception =
                    new GatewayAuthenticationDependencyUnavailableException(
                            GatewayAuthenticationDependencyUnavailableException.REASON_BULKHEAD_REJECTED);
            metrics.recordRejection(route.id(), exception.reasonCode());
            logRejection(route, exception.reasonCode(), true);
            throw exception;
        }
        try {
            return client.authenticate(authorizationHeader);
        } catch (GatewayAuthenticationFailureException exception) {
            metrics.recordRejection(route.id(), "invalid_credentials");
            logRejection(route, "invalid_credentials", false);
            throw exception;
        } catch (GatewayAuthenticationDependencyUnavailableException exception) {
            metrics.recordRejection(route.id(), exception.reasonCode());
            logRejection(route, exception.reasonCode(), true);
            throw exception;
        } finally {
            validationBulkhead.release();
        }
    }

    private static void logRejection(GatewayRoute route, String reason, boolean internalFault) {
        String correlationId =
                RequestContext.CORRELATION_ID.isBound() ? RequestContext.CORRELATION_ID.get() : "unbound";
        if (internalFault) {
            LOGGER.warn(
                    "gateway authentication rejected routeId={} reason={} correlationId={}",
                    route.id(), reason, correlationId);
        } else {
            LOGGER.debug(
                    "gateway authentication rejected routeId={} reason={} correlationId={}",
                    route.id(), reason, correlationId);
        }
    }
}
