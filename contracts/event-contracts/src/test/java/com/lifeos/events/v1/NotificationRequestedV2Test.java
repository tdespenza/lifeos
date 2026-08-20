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
        UUID recipient = UUID.randomUUID();
        NotificationRequestedV2 request = new NotificationRequestedV2(
                UUID.randomUUID(),
                recipient,
                recipient.toString(),
                "calendar.reminder",
                NotificationPriority.NORMAL,
                "Calendar reminder",
                "An upcoming calendar event is starting soon.",
                URI.create("lifeos://calendar/events/abc"),
                Set.of(NotificationChannel.REALTIME),
                Instant.parse("2026-08-17T12:00:00Z"),
                "America/Chicago");

        assertEquals("America/Chicago", request.eventTimeZone());
        assertEquals(recipient, request.asV1().recipientAccountId());
        assertEquals(Set.of(NotificationChannel.REALTIME), request.asV1().requestedChannels());
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
