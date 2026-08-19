package com.lifeos.taskgoal.task.idempotency;

import java.util.List;
import java.util.regex.Pattern;

/** Bounded opaque client key validation shared by every Task mutation. */
public final class TaskIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";
    public static final int MAX_LENGTH = 128;
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$");

    private TaskIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidTaskIdempotencyKeyException();
        }
        return requireValid(values.getFirst());
    }

    public static String requireValid(String value) {
        if (value == null || value.length() > MAX_LENGTH || !SAFE_KEY.matcher(value).matches()) {
            throw new InvalidTaskIdempotencyKeyException();
        }
        return value;
    }
}
