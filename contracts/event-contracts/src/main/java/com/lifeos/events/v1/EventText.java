package com.lifeos.events.v1;

import java.util.regex.Pattern;

/** Package-private validation helpers shared by event envelopes and payload records. */
final class EventText {

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    private EventText() {
    }

    static void requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength || containsUnsafeControl(value)) {
            throw new IllegalArgumentException(field + " must be nonblank, bounded, and free of unsafe control characters");
        }
    }

    static void requireSingleLine(String value, String field, int maximumLength) {
        requireText(value, field, maximumLength);
        if (value.codePoints().anyMatch(character -> character == '\n'
                || character == '\r'
                || character == '\t'
                || Character.getType(character) == Character.LINE_SEPARATOR
                || Character.getType(character) == Character.PARAGRAPH_SEPARATOR)) {
            throw new IllegalArgumentException(field + " must be a single-line value");
        }
    }

    static void requireToken(String value, String field, int maximumLength) {
        requireText(value, field, maximumLength);
        if (!TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
    }

    static boolean containsUnsafeControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n'
                && character != '\r'
                && character != '\t');
    }
}
