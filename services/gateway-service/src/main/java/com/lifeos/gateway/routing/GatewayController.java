package com.lifeos.gateway.routing;

import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.observability.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
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

    private final GatewayRouteTable routeTable;
    private final GatewayForwarder forwarder;

    /**
     * Creates the gateway controller.
     *
     * @param routeTable configured route table
     * @param forwarder bounded HTTP forwarder
     */
    public GatewayController(GatewayRouteTable routeTable, GatewayForwarder forwarder) {
        this.routeTable = routeTable;
        this.forwarder = forwarder;
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
        Optional<GatewayRoute> route = routeTable.resolve(pathWithoutContext(request));
        if (route.isEmpty()) {
            throw new UnknownGatewayRouteException();
        }
        if (!SUPPORTED_METHODS.contains(request.getMethod())) {
            throw new UnsupportedGatewayMethodException();
        }
        String correlationId = correlationId(request);
        forwarder.forward(request, response, route.get(), correlationId);
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
        return ResponseEntity.status(exception.getStatus()).body(problem);
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
