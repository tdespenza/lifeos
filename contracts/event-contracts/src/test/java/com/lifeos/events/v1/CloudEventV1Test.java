package com.lifeos.events.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
}
