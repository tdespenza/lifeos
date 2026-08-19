package com.lifeos.documentvault.api;

import com.lifeos.documentvault.service.DocumentSearchPage;
import java.util.List;

/** Bounded page response intentionally has no global total count. */
public record DocumentSearchResponse(
        List<DocumentSearchResultResponse> results, int page, int size, boolean hasNext, boolean catalogTruncated) {

    static DocumentSearchResponse from(DocumentSearchPage page) {
        return new DocumentSearchResponse(
                page.results().stream().map(DocumentSearchResultResponse::from).toList(),
                page.page(),
                page.size(),
                page.hasNext(),
                page.catalogTruncated());
    }
}
