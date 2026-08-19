package com.lifeos.taskgoal.task.idempotency;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the only accepted strong numeric Task ETag form. */
public final class TaskVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\\\"([0-9]{1,19})\\\"$");

    private TaskVersionPrecondition() {
    }

    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new TaskVersionPreconditionRequiredException();
        }
        if (values.size() != 1) {
            throw new InvalidTaskVersionPreconditionException();
        }
        Matcher matcher = STRONG_NUMERIC_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw new InvalidTaskVersionPreconditionException();
        }
        try {
            long version = Long.parseLong(matcher.group(1));
            if (version < 0) {
                throw new InvalidTaskVersionPreconditionException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new InvalidTaskVersionPreconditionException();
        }
    }
}
