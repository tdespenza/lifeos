package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRequestedV1Test {

    @Test
    void makesRequestedChannelsImmutable() {
        NotificationRequestedV1 request = validRequest(
                "Reminder",
                "Your event starts soon.",
                NotificationPriority.NORMAL,
                URI.create("lifeos://calendar/events/123"),
                EnumSet.of(NotificationChannel.EMAIL, NotificationChannel.REALTIME));

        assertEquals(Set.of(NotificationChannel.EMAIL, NotificationChannel.REALTIME), request.requestedChannels());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.requestedChannels().add(NotificationChannel.PUSH));
    }

    @Test
    void rejectsUnsafeActionUris() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> validRequest(
                                "Reminder", "body", NotificationPriority.NORMAL,
                                URI.create("mailto:person@example.test"),
                                EnumSet.of(NotificationChannel.EMAIL))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> validRequest(
                                "Reminder", "body", NotificationPriority.NORMAL,
                                URI.create("https:notify"),
                                EnumSet.of(NotificationChannel.EMAIL))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> validRequest(
                                "Reminder", "body", NotificationPriority.NORMAL,
                                URI.create("https://user:secret@example.test/notify"),
                                EnumSet.of(NotificationChannel.EMAIL))));
    }

    @Test
    void acceptsAnActionUriAtTheMaximumLength() {
        URI actionUri = actionUriWithLength(2_048);

        validRequest(
                "Reminder", "body", NotificationPriority.NORMAL, actionUri,
                EnumSet.of(NotificationChannel.EMAIL));
    }

    @Test
    void rejectsAnActionUriBeyondTheMaximumLength() {
        URI actionUri = actionUriWithLength(2_049);

        assertThrows(
                IllegalArgumentException.class,
                () -> validRequest(
                        "Reminder", "body", NotificationPriority.NORMAL, actionUri,
                        EnumSet.of(NotificationChannel.EMAIL)));
    }

    private static URI actionUriWithLength(int length) {
        try {
            String prefix = "https://example.test/";
            return new URI("https", "example.test", "/" + "a".repeat(length - prefix.length()), null);
        } catch (URISyntaxException exception) {
            throw new AssertionError("test URI must be valid", exception);
        }
    }

    @Test
    void rejectsInvalidPayloadFields() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> validRequest(
                                "Reminder", "body", NotificationPriority.NORMAL, null,
                                EnumSet.noneOf(NotificationChannel.class))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> validRequest(
                                " ", "body", NotificationPriority.NORMAL, null,
                                EnumSet.of(NotificationChannel.EMAIL))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> validRequest(
                                "Reminder", "b".repeat(4_001), NotificationPriority.NORMAL,
                                null, EnumSet.of(NotificationChannel.EMAIL))),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> validRequest(
                                "Reminder", "body", null, null, EnumSet.of(NotificationChannel.EMAIL))));
    }

    private static NotificationRequestedV1 validRequest(
            String title,
            String body,
            NotificationPriority priority,
            URI actionUri,
            Set<NotificationChannel> requestedChannels) {
        return new NotificationRequestedV1(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "tenant-a",
                "calendar.reminder",
                priority,
                title,
                body,
                actionUri,
                requestedChannels,
                null);
    }
}
