package com.lifeos.documentvault.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentProofOutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void leaseLifecyclePublishesExactlyOnceAndRejectsStaleLease() {
        DocumentProofRequest request = new DocumentProofRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 2L,
                DIGEST, DIGEST, DIGEST, NOW);
        DocumentProofOutboxEvent event = new DocumentProofOutboxEvent(request, "{}", NOW);

        UUID lease = event.claim(NOW, Duration.ofSeconds(30));
        assertThat(event.getAttemptCount()).isEqualTo(1);
        event.markPublished(lease, NOW.plusSeconds(1));

        assertThatThrownBy(() -> event.markPublished(lease, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedLeaseCanBeRescheduledAndThenClaimedAfterBackoff() {
        DocumentProofRequest request = new DocumentProofRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 0L,
                DIGEST, DIGEST, DIGEST, NOW);
        DocumentProofOutboxEvent event = new DocumentProofOutboxEvent(request, "{}", NOW);
        UUID lease = event.claim(NOW, Duration.ofSeconds(1));
        Instant retryAt = NOW.plusSeconds(5);
        event.reschedule(lease, retryAt, "KAFKA_PUBLISH_FAILURE");

        assertThatThrownBy(() -> event.claim(NOW.plusSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(event.claim(retryAt, Duration.ofSeconds(1))).isNotNull();
    }
}
