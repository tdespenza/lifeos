package com.lifeos.taskgoal.goal.idempotency;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the only accepted strong ETag form for a goal lifecycle mutation. */
public final class GoalVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\\\"([0-9]{1,19})\\\"$");

    private GoalVersionPrecondition() {
    }

    /** Requires exactly one non-wildcard strong numeric ETag. */
    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new GoalVersionPreconditionRequiredException();
        }
        if (values.size() != 1) {
            throw new InvalidGoalVersionPreconditionException();
        }
        Matcher matcher = STRONG_NUMERIC_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw new InvalidGoalVersionPreconditionException();
        }
        try {
            long version = Long.parseLong(matcher.group(1));
            if (version < 0) {
                throw new InvalidGoalVersionPreconditionException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new InvalidGoalVersionPreconditionException();
        }
    }
}
