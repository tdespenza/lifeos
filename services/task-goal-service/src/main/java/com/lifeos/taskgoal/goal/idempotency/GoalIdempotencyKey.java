package com.lifeos.taskgoal.goal.idempotency;

import java.util.List;
import java.util.regex.Pattern;

/** Validates the bounded opaque header used to make goal creation replay-safe. */
public final class GoalIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";
    public static final int MAX_LENGTH = 128;

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$");

    private GoalIdempotencyKey() {
    }

    /**
     * Requires exactly one valid key header. The value remains opaque and is never trimmed or
     * normalized, so two distinct keys cannot accidentally collapse to the same operation.
     */
    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidGoalIdempotencyKeyException();
        }
        return requireValid(values.getFirst());
    }

    /** Validates a key at the service boundary as well as the HTTP boundary. */
    public static String requireValid(String value) {
        if (value == null || value.length() > MAX_LENGTH || !SAFE_KEY.matcher(value).matches()) {
            throw new InvalidGoalIdempotencyKeyException();
        }
        return value;
    }
}
