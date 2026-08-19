package com.lifeos.notification.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

/** Canonicalizes a single bounded UUID correlation header for every HTTP request. */
public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = "lifeos.correlationId";

    private CorrelationIdSupport() {
    }

    public static String resolve(HttpServletRequest request) {
        List<String> values = java.util.Collections.list(request.getHeaders(HEADER_NAME));
        if (values.size() != 1) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(values.getFirst().trim()).toString();
        } catch (RuntimeException exception) {
            return UUID.randomUUID().toString();
        }
    }
}
