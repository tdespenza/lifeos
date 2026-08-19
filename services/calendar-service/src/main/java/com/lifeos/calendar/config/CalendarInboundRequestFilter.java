package com.lifeos.calendar.config;

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

/** Local direct-service bulkhead; gateway rate limiting remains the public-edge control. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CalendarInboundRequestFilter extends OncePerRequestFilter {

    private final long maxInboundBodyBytes;
    private final Semaphore permits;

    public CalendarInboundRequestFilter(CalendarProperties properties) {
        maxInboundBodyBytes = properties.getMaxInboundBodyBytes();
        permits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxInboundBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        if (!permits.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        try {
            filterChain.doFilter(new CalendarBoundedRequestWrapper(request, maxInboundBodyBytes), response);
        } catch (CalendarPayloadTooLargeException exception) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            }
        } finally {
            permits.release();
        }
    }
}
