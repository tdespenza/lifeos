package com.lifeos.gateway.graphql;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.auth.GatewayAuthenticationDependencyUnavailableException;
import com.lifeos.gateway.auth.GatewayAuthenticationFailureException;
import com.lifeos.gateway.auth.GatewayAuthenticationService;
import com.lifeos.gateway.ratelimit.GatewayRateLimitDependencyUnavailableException;
import com.lifeos.gateway.ratelimit.GatewayRateLimiter;
import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.routing.GatewayRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the same authentication and rate-limit boundary to GraphQL as REST routes. */
@Component
public class DashboardGraphQlSecurityFilter extends OncePerRequestFilter {

    static final String AUTHORIZATION_ATTRIBUTE = DashboardGraphQlSecurityFilter.class.getName() + ".authorization";
    static final String SUBJECT_ATTRIBUTE = DashboardGraphQlSecurityFilter.class.getName() + ".subject";
    private static final int MAX_QUERY_BYTES = 64 * 1024;
    private static final GatewayRoute ROUTE = new GatewayRoute(
            "graphql-dashboard", "/graphql", URI.create("http://graphql.internal"), true, Set.of("POST"));

    private final GatewayAuthenticationService authenticationService;
    private final GatewayRateLimiter rateLimiter;

    public DashboardGraphQlSecurityFilter(
            GatewayAuthenticationService authenticationService, GatewayRateLimiter rateLimiter) {
        this.authenticationService = authenticationService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!"/graphql".equals(request.getServletPath()) && !"/graphql".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
            response.setHeader(HttpHeaders.ALLOW, "POST");
            return;
        }
        if (request.getContentLengthLong() > MAX_QUERY_BYTES) {
            error(response, request, HttpStatus.PAYLOAD_TOO_LARGE, "GraphQL query exceeds the request limit", false);
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        try {
            rateLimiter.check(ROUTE, request, null);
            GatewayAuthenticatedSubject subject = authenticationService.authenticate(ROUTE, authorization);
            rateLimiter.check(ROUTE, request, subject);
            request.setAttribute(SUBJECT_ATTRIBUTE, subject);
            request.setAttribute(AUTHORIZATION_ATTRIBUTE, authorization);
            filterChain.doFilter(request, response);
        } catch (GatewayAuthenticationFailureException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            error(response, request, HttpStatus.UNAUTHORIZED, "Authentication required", false);
        } catch (GatewayAuthenticationDependencyUnavailableException
                | GatewayRateLimitDependencyUnavailableException exception) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "5");
            error(response, request, HttpStatus.SERVICE_UNAVAILABLE, "GraphQL gateway dependency unavailable", true);
        }
    }

    private static void error(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String message,
            boolean retryable)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        String correlationId = (String) request.getAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE);
        if (correlationId != null) {
            response.setHeader(CorrelationIdSupport.HEADER_NAME, correlationId);
        }
        response.getWriter().write("{\"error\":\"" + message + "\",\"retryable\":" + retryable + "}");
    }
}
