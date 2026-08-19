package com.lifeos.media.idempotency;

import java.util.List;

/** Strict bounded parser; raw values are never persisted or logged. */
public final class MediaIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";

    private MediaIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidMediaIdempotencyKeyException();
        }
        String key = values.getFirst();
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._~-]{15,127}")) {
            throw new InvalidMediaIdempotencyKeyException();
        }
        return key;
    }
}
