package com.lifeos.identity.account;

import java.util.List;
import java.util.regex.Pattern;

/** Validates the bounded opaque header used to make public registration replay-safe. */
public final class AccountRegistrationIdempotencyKey {

    /** HTTP header carrying the opaque registration retry key. */
    public static final String HEADER_NAME = "Idempotency-Key";

    private static final int MAX_LENGTH = 128;
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$");

    private AccountRegistrationIdempotencyKey() {
    }

    /**
     * Requires exactly one opaque key header. Values are not trimmed or normalized, preventing
     * distinct client keys from collapsing into one operation.
     *
     * @param values all received header values
     * @return valid opaque key
     */
    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidAccountRegistrationIdempotencyKeyException();
        }
        return requireValid(values.getFirst());
    }

    /** Validates a key again at the application boundary. */
    public static String requireValid(String value) {
        if (value == null || value.length() > MAX_LENGTH || !SAFE_KEY.matcher(value).matches()) {
            throw new InvalidAccountRegistrationIdempotencyKeyException();
        }
        return value;
    }
}
