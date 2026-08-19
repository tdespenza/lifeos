package com.lifeos.notification.messaging;

import java.util.UUID;

/** Detached outbox lease and immutable broker record material. */
public record ClaimedOutboxEvent(
        UUID id,
        UUID leaseToken,
        String topic,
        String partitionKey,
        String payloadJson,
        String headersJson,
        int attemptCount) {
}
