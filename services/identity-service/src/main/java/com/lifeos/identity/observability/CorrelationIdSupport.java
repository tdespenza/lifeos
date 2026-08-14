package com.lifeos.identity.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates a correlation ID before it enters service logs, context, or responses.
 */
public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdSupport.class.getName() + ".value";

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    private CorrelationIdSupport() {
    }

    /**
     * Preserves one valid UUID from a trusted upstream gateway and generates one for all other
     * inputs.
     *
     * @param request inbound request
     * @return canonical lower-case correlation ID
     */
    public static String resolve(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HEADER_NAME);
        String candidate = null;
        int count = 0;
        while (values != null && values.hasMoreElements()) {
            candidate = values.nextElement();
            count++;
        }
        if (count == 1 && isValid(candidate)) {
            return UUID.fromString(candidate).toString();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Checks the canonical UUID shape before parsing it.
     *
     * @param value candidate value
     * @return whether the value is a valid UUID representation
     */
    public static boolean isValid(String value) {
        return value != null && CANONICAL_UUID.matcher(value).matches();
    }
}
