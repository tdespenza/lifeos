package com.lifeos.events.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 payload emitted for one channel outcome. It is safe for operational consumers: it
 * contains no provider response body, address, device token, or rendered notification body.
 */
public record NotificationDeliveryStatusV1(
        UUID notificationId,
        UUID sourceEventId,
        UUID recipientAccountId,
        NotificationChannel channel,
        NotificationDeliveryOutcome outcome,
        int attempt,
        String reasonCode,
        Instant occurredAt) {

    private static final int MAX_REASON_CODE_LENGTH = 80;

    public NotificationDeliveryStatusV1 {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        Objects.requireNonNull(recipientAccountId, "recipientAccountId must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        CloudEventV1.requireToken(reasonCode, "reasonCode", MAX_REASON_CODE_LENGTH);
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
