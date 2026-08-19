package com.lifeos.assistant.tool;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Strict parser for the assistant tool's durable downstream retry key. */
public final class AssistantToolIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";

    private AssistantToolIdempotencyKey() {
    }

    public static String requireSingle(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Exactly one Idempotency-Key header is required");
        }
        String value = values.getFirst();
        if (value == null || value.length() < 1 || value.length() > 128
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 visible ASCII characters");
        }
        // Force the same byte-level interpretation at the boundary that TaskGoal uses for its hash.
        value.getBytes(StandardCharsets.US_ASCII);
        return value;
    }
}
