package com.lifeos.media.idempotency;

import java.util.List;

/** Parses a strong integer ETag for optimistic Media lifecycle mutations. */
public final class MediaVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";

    private MediaVersionPrecondition() {
    }

    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw new MediaVersionPreconditionRequiredException();
        }
        String raw = values.getFirst().trim();
        if (raw.length() < 3 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            throw new InvalidMediaVersionPreconditionException();
        }
        String value = raw.substring(1, raw.length() - 1);
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new InvalidMediaVersionPreconditionException();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidMediaVersionPreconditionException();
        }
    }
}
