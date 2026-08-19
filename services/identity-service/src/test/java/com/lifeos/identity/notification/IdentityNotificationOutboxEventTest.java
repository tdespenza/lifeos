package com.lifeos.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityNotificationOutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void leaseLifecycleSupportsPublishAndRejectsStaleLeases() {
        IdentityNotificationOutboxEvent event = pending();

        UUID lease = event.claim(NOW, Duration.ofSeconds(30));
        assertThat(event.getState()).isEqualTo(IdentityNotificationOutboxState.IN_FLIGHT);
        assertThat(event.getAttemptCount()).isEqualTo(1);

        event.markPublished(lease, NOW.plusSeconds(1));
        assertThat(event.getState()).isEqualTo(IdentityNotificationOutboxState.PUBLISHED);
        assertThatThrownBy(() -> event.claim(NOW.plusSeconds(2), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abandonedLeaseCanBeReclaimedAndDeadLettered() {
        IdentityNotificationOutboxEvent event = pending();

        UUID firstLease = event.claim(NOW, Duration.ofSeconds(1));
        assertThatThrownBy(() -> event.markPublished(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);
        UUID secondLease = event.claim(NOW.plusSeconds(2), Duration.ofSeconds(1));
        assertThat(secondLease).isNotEqualTo(firstLease);
        event.deadLetter(secondLease, "KAFKA_PUBLISH_FAILURE");

        assertThat(event.getState()).isEqualTo(IdentityNotificationOutboxState.DEAD_LETTER);
        assertThat(event.getLastErrorCode()).isEqualTo("KAFKA_PUBLISH_FAILURE");
    }

    private static IdentityNotificationOutboxEvent pending() {
        return IdentityNotificationOutboxEvent.pending(
                UUID.randomUUID(),
                "lifeos.notification.requested.v2",
                UUID.randomUUID().toString(),
                "com.lifeos.notification.requested.v2",
                "{}",
                "{}",
                NOW);
    }
}
