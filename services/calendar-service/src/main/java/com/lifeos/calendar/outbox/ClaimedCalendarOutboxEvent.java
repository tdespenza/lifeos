package com.lifeos.calendar.outbox;

import java.util.UUID;

/** Immutable relay lease handoff; payload was already committed in Calendar's outbox. */
public record ClaimedCalendarOutboxEvent(
        UUID id,
        UUID leaseToken,
        String topic,
        String partitionKey,
        String payloadJson,
        String headersJson,
        int attemptCount) {
}
