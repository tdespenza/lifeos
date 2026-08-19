package com.lifeos.finance.idempotency;

import java.util.List;
import java.util.regex.Pattern;

/** Parses the one opaque, bounded idempotency header accepted by all Finance mutations. */
public final class FinanceIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    private FinanceIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidFinanceIdempotencyKeyException();
        }
        return requireValid(values.getFirst());
    }

    public static String requireValid(String value) {
        if (value == null || !VALID_KEY.matcher(value).matches()) {
            throw new InvalidFinanceIdempotencyKeyException();
        }
        return value;
    }
}
