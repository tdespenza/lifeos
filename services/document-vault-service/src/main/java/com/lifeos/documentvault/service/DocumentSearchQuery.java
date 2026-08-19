package com.lifeos.documentvault.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded searchable metadata term and fixed maximum page size prevent unbounded owner scans. */
public record DocumentSearchQuery(String value, int page, int size) {

    private static final Pattern SAFE_QUERY = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .,'-]{1,127}");
    private static final int MAX_PAGE = 1_000;
    private static final int MAX_PAGE_SIZE = 50;

    public DocumentSearchQuery {
        if (value == null) {
            throw new IllegalArgumentException("search query is required");
        }
        value = value.trim();
        if (!SAFE_QUERY.matcher(value).matches()) {
            throw new IllegalArgumentException("search query is invalid");
        }
        value = value.toLowerCase(Locale.ROOT);
        if (page < 0 || page > MAX_PAGE) {
            throw new IllegalArgumentException("search page is outside the supported range");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("search page size is outside the supported range");
        }
    }
}
