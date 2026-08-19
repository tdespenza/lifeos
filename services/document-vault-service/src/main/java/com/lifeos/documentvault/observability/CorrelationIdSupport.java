package com.lifeos.documentvault.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates correlation identifiers before they enter response headers, MDC, or scoped context. */
public final class CorrelationIdSupport {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = "lifeos.document-vault.correlation-id";
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
        return count == 1 && candidate != null && CANONICAL_UUID.matcher(candidate).matches()
                ? UUID.fromString(candidate).toString()
                : UUID.randomUUID().toString();
    }
}
