package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRequestedV1Test {

    @Test
    void makesRequestedChannelsImmutableAndRejectsContactData() {
        NotificationRequestedV1 request = new NotificationRequestedV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "tenant-a",
                "calendar.reminder",
                NotificationPriority.NORMAL,
                "Reminder",
                "Your event starts soon.",
                URI.create("lifeos://calendar/events/123"),
                EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.REALTIME),
                null);

        assertEquals(Set.of(NotificationChannel.EMAIL, NotificationChannel.REALTIME), request.requestedChannels());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.requestedChannels().add(NotificationChannel.PUSH));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationRequestedV1(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "tenant-a",
                        "calendar.reminder",
                        NotificationPriority.NORMAL,
                        "Reminder",
                        "body",
                        URI.create("mailto:person@example.test"),
                        EnumSet.of(NotificationChannel.EMAIL),
                        null));
    }
}
