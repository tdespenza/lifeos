package com.lifeos.trustledger.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates one correlation ID before it enters structured logs or request scope. */
public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-ID";
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    private CorrelationIdSupport() {
    }

    public static String resolve(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HEADER_NAME);
        String candidate = null;
        int count = 0;
        while (values != null && values.hasMoreElements()) {
            candidate = values.nextElement();
            count++;
        }
        if (count == 1 && candidate != null && CANONICAL_UUID.matcher(candidate).matches()) {
            return UUID.fromString(candidate).toString();
        }
        return UUID.randomUUID().toString();
    }
}
