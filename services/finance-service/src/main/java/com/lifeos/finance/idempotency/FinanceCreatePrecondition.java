package com.lifeos.finance.idempotency;

import java.util.List;

/** Strict parser for creation's required {@code If-None-Match: *} precondition. */
public final class FinanceCreatePrecondition {

    public static final String HEADER_NAME = "If-None-Match";

    private FinanceCreatePrecondition() {
    }

    public static void requireCreateOnly(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new FinanceCreatePreconditionRequiredException();
        }
        if (values.size() != 1 || !"*".equals(values.getFirst())) {
            throw new InvalidFinancePreconditionException();
        }
    }
}
