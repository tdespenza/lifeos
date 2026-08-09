package com.lifeos.identity.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a bounded request correlation identifier for logs, traces, and responses.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * HTTP header used to carry the request correlation identifier.
     */
    public static final String HEADER_NAME = "X-Correlation-ID";

    /**
     * Creates a correlation-ID filter managed by Spring.
     */
    public CorrelationIdFilter() {
    }

    /**
     * Adds the correlation identifier to the response, logging context, and request-scoped context.
     *
     * @param request current HTTP request
     * @param response current HTTP response
     * @param filterChain remaining servlet filter chain
     * @throws IOException when the downstream request cannot be processed
     * @throws ServletException when the downstream servlet cannot be processed
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = UUID.randomUUID().toString();
        response.setHeader(HEADER_NAME, correlationId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            try {
                ScopedValue.where(RequestContext.CORRELATION_ID, correlationId).call(() -> {
                    filterChain.doFilter(request, response);
                    return null;
                });
            } catch (IOException | ServletException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ServletException("Request context propagation failed", exception);
            }
        }
    }
}
