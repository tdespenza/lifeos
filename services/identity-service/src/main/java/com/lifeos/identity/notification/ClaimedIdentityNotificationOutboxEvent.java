package com.lifeos.identity.notification;

import java.util.UUID;

/** Immutable lease handoff from the transactional claim boundary to the Kafka publisher. */
public record ClaimedIdentityNotificationOutboxEvent(
        UUID id,
        UUID leaseToken,
        String topic,
        String partitionKey,
        String payloadJson,
        String headersJson,
        int attemptCount) {
}
