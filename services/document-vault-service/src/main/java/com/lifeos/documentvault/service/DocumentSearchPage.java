package com.lifeos.documentvault.service;

import java.util.List;

/** Bounded owner-filtered search page with no global total-count query. */
public record DocumentSearchPage(
        List<DocumentSearchResult> results, int page, int size, boolean hasNext, boolean catalogTruncated) {
}
