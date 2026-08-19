package com.lifeos.profile.idempotency;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requires the only accepted strong ETag form for existing Profile service representations. */
public final class ProfileVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\\\"([0-9]{1,19})\\\"$");

    private ProfileVersionPrecondition() {
    }

    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new ProfileVersionPreconditionRequiredException();
        }
        if (values.size() != 1) {
            throw new InvalidProfileVersionPreconditionException();
        }
        Matcher matcher = STRONG_NUMERIC_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw new InvalidProfileVersionPreconditionException();
        }
        try {
            long version = Long.parseLong(matcher.group(1));
            if (version < 0) {
                throw new InvalidProfileVersionPreconditionException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new InvalidProfileVersionPreconditionException();
        }
    }
}
