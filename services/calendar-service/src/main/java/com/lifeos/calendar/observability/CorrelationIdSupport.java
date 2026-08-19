package com.lifeos.calendar.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates a correlation ID before it enters Calendar logs, audit facts, or downstream calls. */
public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdSupport.class.getName() + ".value";

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
        return count == 1 && isValid(candidate) ? UUID.fromString(candidate).toString() : UUID.randomUUID().toString();
    }

    public static boolean isValid(String value) {
        return value != null && CANONICAL_UUID.matcher(value).matches();
    }
}
