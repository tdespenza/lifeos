package com.lifeos.notification.endpoint;

import java.util.List;

/** Validates the single opaque retry key required for endpoint enrollment. */
public final class EndpointIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";
    private static final int MINIMUM_LENGTH = 16;
    private static final int MAXIMUM_LENGTH = 255;

    private EndpointIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidEndpointIdempotencyKeyException();
        }
        String value = values.getFirst();
        if (value == null || value.length() < MINIMUM_LENGTH || value.length() > MAXIMUM_LENGTH
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidEndpointIdempotencyKeyException();
        }
        return value;
    }
}
