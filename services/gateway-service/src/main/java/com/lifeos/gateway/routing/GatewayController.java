package com.lifeos.gateway.routing;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.auth.GatewayAuthenticationDependencyUnavailableException;
import com.lifeos.gateway.auth.GatewayAuthenticationFailureException;
import com.lifeos.gateway.auth.GatewayAuthenticationService;
import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.observability.RequestContext;
import com.lifeos.gateway.ratelimit.GatewayRateLimitDependencyUnavailableException;
import com.lifeos.gateway.ratelimit.GatewayRateLimitExceededException;
import com.lifeos.gateway.ratelimit.GatewayRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single public ingress controller for the configured gateway route table.
 */
@RestController
public class GatewayController {

    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final int MIN_RETRY_AFTER_SECONDS = 5;
    private static final int MAX_RETRY_AFTER_SECONDS = 15;

    private final GatewayRouteTable routeTable;
    private final GatewayForwarder forwarder;
    private final GatewayAuthenticationService authenticationService;
    private final GatewayRateLimiter rateLimiter;

    /**
     * Creates the production controller with the distributed request-admission boundary.
     *
     * @param routeTable configured route table
     * @param forwarder bounded HTTP forwarder
     * @param authenticationService identity-service authentication boundary
     * @param rateLimiter Redis-backed rate limiter
     */
    @Autowired
    public GatewayController(
            GatewayRouteTable routeTable,
            GatewayForwarder forwarder,
            GatewayAuthenticationService authenticationService,
            GatewayRateLimiter rateLimiter) {
        this.routeTable = routeTable;
        this.forwarder = forwarder;
        this.authenticationService = Objects.requireNonNull(authenticationService, "authenticationService must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
    }

    /**
     * Resolves and forwards all supported public HTTP methods.
     *
     * @param request inbound request
     * @param response outbound response
     * @throws IOException when request or response I/O fails
     */
    @RequestMapping(value = "/{*path}")
    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestPath = pathWithoutContext(request);
        Optional<GatewayRoute> route = routeTable.resolve(requestPath);
        if (route.isEmpty()) {
            throw new UnknownGatewayRouteException();
        }
        if (!SUPPORTED_METHODS.contains(request.getMethod())) {
            throw new UnsupportedGatewayMethodException();
        }
        String correlationId = correlationId(request);
        GatewayRoute resolvedRoute = route.get();
        GatewayAuthenticatedSubject subject = null;
        if (resolvedRoute.requiresAuthentication(requestPath, request.getMethod())) {
            // Charge anonymous attempts before identity validation so invalid credentials cannot
            // consume the identity dependency without consuming an address budget.
            rateLimiter.check(resolvedRoute, request, null);
            subject = authenticationService.authenticate(
                    resolvedRoute, request.getHeader(HttpHeaders.AUTHORIZATION));
        }
        // Protected traffic receives a second, independent charge keyed by the validated account.
        // Public traffic is charged once by its immediate client address.
        rateLimiter.check(resolvedRoute, request, subject);
        forwarder.forward(request, response, resolvedRoute, correlationId, subject);
    }

    /**
     * Returns a controlled RFC 9457 response when the route/client budget is exhausted.
     *
     * @param exception bounded rate-limit metadata
     * @return too-many-requests response with safe retry guidance
     */
    @ExceptionHandler(GatewayRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(GatewayRateLimitExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "The request rate limit has been exceeded.");
        problem.setTitle("Too many requests");
        problem.setProperty("code", "RATE_LIMIT_EXCEEDED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.getRetryAfterSeconds()))
                .header("RateLimit-Limit", Integer.toString(exception.getLimit()))
                .header("RateLimit-Remaining", "0")
                .header("RateLimit-Reset", Integer.toString(exception.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * Returns a fail-closed response when Redis cannot make a safe admission decision.
     *
     * @return generic temporary-failure problem detail
     */
    @ExceptionHandler(GatewayRateLimitDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitDependencyFailure() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Request admission is temporarily unavailable.");
        problem.setTitle("Rate limiter unavailable");
        problem.setProperty("code", "RATE_LIMITER_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    /**
     * Returns one controlled response for an absent, malformed, expired, or revoked bearer.
     *
     * @return generic RFC 9457 unauthorized problem detail
     */
    @ExceptionHandler(GatewayAuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication failed.");
        problem.setTitle("Authentication required");
        problem.setProperty("code", "AUTHENTICATION_REQUIRED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    /**
     * Returns a controlled fail-closed response when identity validation cannot complete.
     *
     * @return generic RFC 9457 temporary-failure problem detail
     */
    @ExceptionHandler(GatewayAuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationDependencyFailure() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable.");
        problem.setTitle("Authentication unavailable");
        problem.setProperty("code", "AUTHENTICATION_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds()))
                .body(problem);
    }

    private static int retryAfterSeconds() {
        return ThreadLocalRandom.current().nextInt(MIN_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS + 1);
    }

    /**
     * Returns one controlled response when the raw request target cannot be forwarded safely.
     *
     * @return generic RFC 9457 problem detail
     */
    @ExceptionHandler(GatewayBadRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequestTarget() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request target is invalid.");
        problem.setTitle("Invalid request target");
        problem.setProperty("code", "INVALID_REQUEST_TARGET");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Returns one controlled response for an unknown route.
     *
     * @return generic RFC 9457 problem detail
     */
    @ExceptionHandler(UnknownGatewayRouteException.class)
    public ResponseEntity<ProblemDetail> handleUnknownRoute() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "The requested API route does not exist.");
        problem.setTitle("Route not found");
        problem.setProperty("code", "ROUTE_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Rejects methods that the gateway will not proxy, such as TRACE and CONNECT.
     *
     * @return generic RFC 9457 problem detail
     */
    @ExceptionHandler(UnsupportedGatewayMethodException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMethod() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, "The requested HTTP method is not supported.");
        problem.setTitle("Method not allowed");
        problem.setProperty("code", "METHOD_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
                .body(problem);
    }

    /**
     * Returns a controlled upstream failure without exposing topology or exception details.
     *
     * @param exception upstream classification
     * @return generic RFC 9457 problem detail
     */
    @ExceptionHandler(GatewayUpstreamException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamFailure(GatewayUpstreamException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatus(), "The requested service is temporarily unavailable.");
        problem.setTitle(exception.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                ? "Upstream timeout"
                : "Upstream unavailable");
        problem.setProperty("code", exception.getStatus() == HttpStatus.GATEWAY_TIMEOUT
                ? "UPSTREAM_TIMEOUT"
                : "UPSTREAM_UNAVAILABLE");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.getStatus());
        if (exception.getRetryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.getRetryAfterSeconds()));
        }
        return response.body(problem);
    }

    /**
     * Returns a controlled request-size failure.
     *
     * @return generic RFC 9457 problem detail
     */
    @ExceptionHandler(GatewayPayloadTooLargeException.class)
    public ResponseEntity<ProblemDetail> handlePayloadTooLarge() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "The request exceeds the configured size limit.");
        problem.setTitle("Payload too large");
        problem.setProperty("code", "PAYLOAD_TOO_LARGE");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /**
     * Returns a controlled response when bounded request-body buffering is at capacity.
     *
     * @return generic temporary-capacity problem detail
     */
    @ExceptionHandler(GatewayRequestBodyCapacityException.class)
    public ResponseEntity<ProblemDetail> handleRequestBodyCapacity() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Request body capacity is temporarily unavailable.");
        problem.setTitle("Request capacity unavailable");
        problem.setProperty("code", "REQUEST_BODY_CAPACITY");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private static String correlationId(HttpServletRequest request) {
        Object requestValue = request.getAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE);
        if (requestValue instanceof String value && CorrelationIdSupport.isValid(value)) {
            return value;
        }
        if (RequestContext.CORRELATION_ID.isBound()) {
            return RequestContext.CORRELATION_ID.get();
        }
        return CorrelationIdSupport.resolve(request);
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return requestUri;
        }
        if (requestUri == null || !requestUri.startsWith(contextPath)) {
            throw new GatewayBadRequestException();
        }
        return requestUri.substring(contextPath.length());
    }

    private static final class UnknownGatewayRouteException extends RuntimeException {

        private UnknownGatewayRouteException() {
            super(null, null, false, false);
        }
    }

    private static final class UnsupportedGatewayMethodException extends RuntimeException {

        private UnsupportedGatewayMethodException() {
            super(null, null, false, false);
        }
    }
}
