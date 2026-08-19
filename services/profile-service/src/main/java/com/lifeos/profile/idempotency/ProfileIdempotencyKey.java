package com.lifeos.profile.idempotency;

import java.util.List;

/** Parses a bounded, opaque client retry key without ever retaining the raw value in persistence. */
public final class ProfileIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";

    private ProfileIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidProfileIdempotencyKeyException();
        }
        return requireValid(values.getFirst());
    }

    static String requireValid(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{8,128}")) {
            throw new InvalidProfileIdempotencyKeyException();
        }
        return value;
    }
}
