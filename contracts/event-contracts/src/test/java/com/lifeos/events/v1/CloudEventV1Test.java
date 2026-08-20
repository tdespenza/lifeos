package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CloudEventV1Test {

    @Test
    void acceptsACloudEventsOnePointZeroEnvelope() {
        assertDoesNotThrow(() -> new CloudEventV1<>(
                UUID.randomUUID(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:calendar-service"),
                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                "notification/7fb08020-53d3-4d36-b053-a9c2e72cccea",
                Instant.parse("2026-08-17T12:00:00Z"),
                "application/json",
                UUID.randomUUID(),
                "payload"));
    }

    @Test
    void rejectsNonCanonicalEnvelopeDetails() {
        assertThrows(IllegalArgumentException.class, () -> new CloudEventV1<>(
                UUID.randomUUID(),
                "0.3",
                URI.create("https://calendar.example.test"),
                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                "notification/1",
                Instant.now(),
                "application/json",
                UUID.randomUUID(),
                "payload"));
        assertThrows(IllegalArgumentException.class, () -> new CloudEventV1<>(
                UUID.randomUUID(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("https://calendar.example.test?secret=no"),
                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                "notification/1",
                Instant.now(),
                "application/json",
                UUID.randomUUID(),
                "payload"));
    }

    @Test
    void rejectsNullRequiredAttributes() {
        assertThrows(NullPointerException.class, () -> requiredAttributeEnvelope(
                null, URI.create("https://calendar.example.test"), Instant.now()));
        assertThrows(NullPointerException.class, () -> requiredAttributeEnvelope(
                UUID.randomUUID(), null, Instant.now()));
        assertThrows(NullPointerException.class, () -> requiredAttributeEnvelope(
                UUID.randomUUID(), URI.create("https://calendar.example.test"), null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEnvelopes")
    void rejectsInvalidEnvelopeValues(String description, Executable invalidEnvelope) {
        assertThrows(IllegalArgumentException.class, invalidEnvelope, description);
    }

    private static Stream<Arguments> invalidEnvelopes() {
        return Stream.of(
                Arguments.of(
                        "relative source",
                        (Executable) () -> envelope(URI.create("calendar"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "source user info",
                        (Executable) () -> envelope(URI.create("https://user:secret@calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "source fragment",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test/path#fragment"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "oversized source",
                        (Executable) () -> envelope(
                                oversizedSourceUri(),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "non-json content type",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "text/plain", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "null correlation id",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "application/json", null, "payload")),
                Arguments.of(
                        "null data",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1", "application/json", UUID.randomUUID(), null)),
                Arguments.of(
                        "invalid type token",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                "notification type", "notification/1", "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "oversized subject",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "s".repeat(256), "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "subject with line breaks",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1\r\ninjected", "application/json", UUID.randomUUID(), "payload")),
                Arguments.of(
                        "subject with unicode line separator",
                        (Executable) () -> envelope(URI.create("https://calendar.example.test"),
                                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                                "notification/1\u2028injected", "application/json", UUID.randomUUID(), "payload")));
    }

    private static URI oversizedSourceUri() {
        try {
            return new URI("https", "calendar.example.test", "/" + "a".repeat(2048), null);
        } catch (URISyntaxException exception) {
            throw new AssertionError("test URI must be valid", exception);
        }
    }

    private static CloudEventV1<String> requiredAttributeEnvelope(UUID id, URI source, Instant time) {
        return new CloudEventV1<>(
                id,
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                source,
                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                "notification/1",
                time,
                "application/json",
                UUID.randomUUID(),
                "payload");
    }

    private static CloudEventV1<String> envelope(
            URI source,
            String type,
            String subject,
            String contentType,
            UUID correlationId,
            String data) {
        return new CloudEventV1<>(
                UUID.randomUUID(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                source,
                type,
                subject,
                Instant.parse("2026-08-17T12:00:00Z"),
                contentType,
                correlationId,
                data);
    }
}
