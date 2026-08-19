package com.lifeos.documentvault.idempotency;

import java.util.List;
import java.util.regex.Pattern;

/** Bounded opaque key validation shared by upload and metadata-update commands. */
public final class DocumentIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$");

    private DocumentIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidDocumentIdempotencyKeyException();
        }
        return requireValid(values.getFirst());
    }

    public static String requireValid(String value) {
        if (value == null || !SAFE_KEY.matcher(value).matches()) {
            throw new InvalidDocumentIdempotencyKeyException();
        }
        return value;
    }
}
