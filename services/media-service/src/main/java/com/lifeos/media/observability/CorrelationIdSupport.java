package com.lifeos.media.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Safely normalizes caller correlation IDs without letting a header become log content. */
public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = "media.correlationId";

    private CorrelationIdSupport() {
    }

    public static String resolve(HttpServletRequest request) {
        String candidate = request.getHeader(HEADER_NAME);
        if (candidate != null && candidate.matches("[A-Za-z0-9._-]{1,64}")) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
