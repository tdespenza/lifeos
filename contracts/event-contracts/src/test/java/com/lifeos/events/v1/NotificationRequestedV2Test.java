package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRequestedV2Test {

    @Test
    void preservesTheV1DeliverySubsetAndAcceptsRegionTimeZones() {
        UUID notificationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        String tenantId = recipient.toString();
        String category = "calendar.reminder";
        NotificationPriority priority = NotificationPriority.NORMAL;
        String title = "Calendar reminder";
        String body = "An upcoming calendar event is starting soon.";
        URI actionUri = URI.create("lifeos://calendar/events/abc");
        Set<NotificationChannel> requestedChannels = Set.of(NotificationChannel.REALTIME);
        Instant expiresAt = Instant.parse("2026-08-18T12:00:00Z");
        NotificationRequestedV2 request = new NotificationRequestedV2(
                notificationId,
                recipient,
                tenantId,
                category,
                priority,
                title,
                body,
                actionUri,
                requestedChannels,
                expiresAt,
                "America/Chicago");

        assertEquals("America/Chicago", request.eventTimeZone());
        assertEquals(new NotificationRequestedV1(
                notificationId,
                recipient,
                tenantId,
                category,
                priority,
                title,
                body,
                actionUri,
                requestedChannels,
                expiresAt), request.asV1());
        assertEquals(recipient, request.asV1().recipientAccountId());
        assertEquals(requestedChannels, request.asV1().requestedChannels());
    }

    @Test
    void rejectsAnUnknownTimeZone() {
        UUID recipient = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new NotificationRequestedV2(
                        UUID.randomUUID(),
                        recipient,
                        recipient.toString(),
                        "calendar.reminder",
                        NotificationPriority.NORMAL,
                        "Calendar reminder",
                        "An upcoming calendar event is starting soon.",
                        null,
                        Set.of(NotificationChannel.EMAIL),
                        null,
                        "Mars/Olympus"));
        assertTrue(exception.getMessage().contains("eventTimeZone"));
    }

    @Test
    void rejectsFixedOffsetTimeZones() {
        UUID recipient = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new NotificationRequestedV2(
                UUID.randomUUID(),
                recipient,
                recipient.toString(),
                "calendar.reminder",
                NotificationPriority.NORMAL,
                "Calendar reminder",
                "An upcoming calendar event is starting soon.",
                null,
                Set.of(NotificationChannel.EMAIL),
                null,
                "+05:00"));
    }

    @Test
    void rejectsNullTimeZone() {
        assertThrows(IllegalArgumentException.class, () -> requestWithTimeZone(null));
    }

    @Test
    void rejectsBlankTimeZone() {
        assertThrows(IllegalArgumentException.class, () -> requestWithTimeZone("  "));
    }

    @Test
    void rejectsOverlongTimeZone() {
        assertThrows(IllegalArgumentException.class, () -> requestWithTimeZone("A".repeat(65)));
    }

    private static NotificationRequestedV2 requestWithTimeZone(String eventTimeZone) {
        UUID recipient = UUID.randomUUID();
        return new NotificationRequestedV2(
                UUID.randomUUID(),
                recipient,
                recipient.toString(),
                "calendar.reminder",
                NotificationPriority.NORMAL,
                "Calendar reminder",
                "An upcoming calendar event is starting soon.",
                null,
                Set.of(NotificationChannel.EMAIL),
                null,
                eventTimeZone);
    }
}
