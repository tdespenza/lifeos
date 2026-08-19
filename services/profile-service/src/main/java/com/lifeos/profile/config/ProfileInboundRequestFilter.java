package com.lifeos.profile.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds direct service traffic before servlet threads or JSON deserialization can grow without
 * limit. Gateway rate limiting remains the public-edge control; this is a local bulkhead for
 * callers that reach the service through an approved internal network path.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProfileInboundRequestFilter extends OncePerRequestFilter {

    private final long maxInboundBodyBytes;
    private final Semaphore permits;

    public ProfileInboundRequestFilter(ProfileServiceProperties properties) {
        maxInboundBodyBytes = properties.getMaxInboundBodyBytes();
        permits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxInboundBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        try {
            filterChain.doFilter(new ProfileBoundedRequestWrapper(request, maxInboundBodyBytes), response);
        } catch (ProfilePayloadTooLargeException exception) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            }
        } finally {
            permits.release();
        }
    }
}
