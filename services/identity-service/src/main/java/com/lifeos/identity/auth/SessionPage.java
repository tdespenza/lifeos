package com.lifeos.identity.auth;

import java.util.List;

/**
 * Bounded cursor page of safe session projections.
 *
 * @param sessions page contents
 * @param nextCursor opaque cursor for the next page, or null at the end
 */
public record SessionPage(List<SessionSummary> sessions, String nextCursor) {

    /**
     * Defensively copies the page contents so the response cannot be mutated after creation.
     */
    public SessionPage {
        sessions = List.copyOf(sessions == null ? List.of() : sessions);
    }
}
