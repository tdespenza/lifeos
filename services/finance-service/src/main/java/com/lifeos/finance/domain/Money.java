package com.lifeos.finance.domain;

import java.util.Currency;
import java.util.Locale;

/** Currency validation and integer-minor-unit arithmetic guardrails used by the Finance domain. */
public final class Money {

    private Money() {
    }

    /** Returns an ISO 4217 code; currencies are never converted or represented as floating point. */
    public static String requireCurrency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an uppercase ISO 4217 code");
        }
        try {
            Currency currency = Currency.getInstance(value.toUpperCase(Locale.ROOT));
            if (currency.getCurrencyCode().length() != 3) {
                throw new IllegalArgumentException("currency must be an ISO 4217 code");
            }
            return currency.getCurrencyCode();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("currency must be a supported ISO 4217 code", exception);
        }
    }

    public static long requirePositiveMinor(long amountMinor, String name) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer minor-unit amount");
        }
        return amountMinor;
    }

    public static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("minor-unit amount exceeds the supported range", exception);
        }
    }
}
