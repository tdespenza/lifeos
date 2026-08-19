package com.lifeos.finance.idempotency;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for the strong numeric ETags emitted by Finance representations. */
public final class FinanceVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("\\\"([0-9]{1,18})\\\"");

    private FinanceVersionPrecondition() {
    }

    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new FinanceVersionPreconditionRequiredException();
        }
        if (values.size() != 1) {
            throw new InvalidFinancePreconditionException();
        }
        Matcher matcher = STRONG_NUMERIC_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw new InvalidFinancePreconditionException();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new InvalidFinancePreconditionException();
        }
    }
}
