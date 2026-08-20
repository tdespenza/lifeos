package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeliveryStatusV1Test {

    @Test
    void acceptsValidDeliveryStatus() {
        assertDoesNotThrow(() -> new NotificationDeliveryStatusV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                NotificationDeliveryOutcome.DELIVERED,
                1,
                "provider-accepted",
                Instant.parse("2026-08-17T12:00:00Z")));
    }

    @Test
    void rejectsInvalidDeliveryStatusFields() {
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        UUID recipientAccountId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-17T12:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> new NotificationDeliveryStatusV1(
                notificationId, sourceEventId, recipientAccountId, NotificationChannel.EMAIL,
                NotificationDeliveryOutcome.DELIVERED, -1, "provider-accepted", occurredAt));
        assertThrows(NullPointerException.class, () -> new NotificationDeliveryStatusV1(
                notificationId, sourceEventId, recipientAccountId, null,
                NotificationDeliveryOutcome.DELIVERED, 1, "provider-accepted", occurredAt));
        assertThrows(NullPointerException.class, () -> new NotificationDeliveryStatusV1(
                notificationId, sourceEventId, recipientAccountId, NotificationChannel.EMAIL,
                null, 1, "provider-accepted", occurredAt));
        assertThrows(IllegalArgumentException.class, () -> new NotificationDeliveryStatusV1(
                notificationId, sourceEventId, recipientAccountId, NotificationChannel.EMAIL,
                NotificationDeliveryOutcome.DELIVERED, 1, "provider response leaked", occurredAt));
    }
}
