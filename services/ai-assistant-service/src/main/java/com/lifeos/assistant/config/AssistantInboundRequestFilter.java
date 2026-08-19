package com.lifeos.assistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.api.AssistantDtos;
import com.lifeos.assistant.observability.CorrelationIdSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local traffic bulkhead for requests that bypass the edge gateway. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AssistantInboundRequestFilter extends OncePerRequestFilter {

    private final long maxInboundBodyBytes;
    private final Semaphore permits;
    private final ObjectMapper objectMapper;

    public AssistantInboundRequestFilter(AiAssistantProperties properties, ObjectMapper objectMapper) {
        maxInboundBodyBytes = properties.getMaxInboundBodyBytes();
        permits = new Semaphore(properties.getMaxConcurrentRequests(), true);
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxInboundBodyBytes) {
            writeRejection(
                    request,
                    response,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "Request payload too large",
                    false);
            return;
        }
        if (!permits.tryAcquire()) {
            writeRejection(
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "INBOUND_REQUEST_CAPACITY_EXCEEDED",
                    "Assistant request capacity is temporarily unavailable",
                    true);
            return;
        }
        try {
            filterChain.doFilter(new AssistantBoundedRequestWrapper(request, maxInboundBodyBytes), response);
        } catch (AssistantPayloadTooLargeException exception) {
            if (!response.isCommitted()) {
                writeRejection(
                        request,
                        response,
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "PAYLOAD_TOO_LARGE",
                        "Request payload too large",
                        false);
            }
        } finally {
            permits.release();
        }
    }

    private void writeRejection(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            boolean retryable)
            throws IOException {
        String correlationId = resolveCorrelationId(request, response);
        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(CorrelationIdSupport.HEADER_NAME, correlationId);
        if (retryable) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        }
        objectMapper.writeValue(
                response.getOutputStream(), new AssistantDtos.ErrorResponse(code, message, retryable, correlationId));
    }

    private static String resolveCorrelationId(HttpServletRequest request, HttpServletResponse response) {
        Object attribute = request.getAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE);
        if (attribute instanceof String correlationId && CorrelationIdSupport.isValid(correlationId)) {
            return correlationId;
        }
        String correlationId = CorrelationIdSupport.resolve(request);
        request.setAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationIdSupport.HEADER_NAME, correlationId);
        return correlationId;
    }
}
