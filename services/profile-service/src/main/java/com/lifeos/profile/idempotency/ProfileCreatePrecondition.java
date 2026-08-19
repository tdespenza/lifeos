package com.lifeos.profile.idempotency;

import java.util.List;

/** Requires an explicit create-only condition rather than silently overwriting an existing scope. */
public final class ProfileCreatePrecondition {

    public static final String HEADER_NAME = "If-None-Match";

    private ProfileCreatePrecondition() {
    }

    public static void requireCreateOnly(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new ProfileCreatePreconditionRequiredException();
        }
        if (values.size() != 1 || !"*".equals(values.getFirst())) {
            throw new InvalidProfileCreatePreconditionException();
        }
    }
}
