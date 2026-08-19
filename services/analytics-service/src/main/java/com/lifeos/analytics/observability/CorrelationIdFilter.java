package com.lifeos.analytics.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes the only accepted inbound correlation context for analytics requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = CorrelationIdSupport.resolve(request);
        request.setAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationIdSupport.HEADER_NAME, correlationId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            try {
                ScopedValue.where(RequestContext.CORRELATION_ID, correlationId)
                        .call(() -> {
                            filterChain.doFilter(request, response);
                            return null;
                        });
            } catch (IOException | ServletException | RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ServletException("request context propagation failed", exception);
            }
        }
    }
}
