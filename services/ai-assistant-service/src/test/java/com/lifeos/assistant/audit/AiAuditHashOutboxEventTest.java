package com.lifeos.assistant.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiAuditHashOutboxEventTest {

    private static final String CLIENT_DIGEST = "a".repeat(64);
    private static final String AUDIT_DIGEST = "b".repeat(64);

    @Test
    void leasesAreSingleUseAndCanBeFinalizedExactlyOnce() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AiAuditHashOutboxEvent event = new AiAuditHashOutboxEvent(
                auditEvent(),
                "com.lifeos.ai.audit.hash.requested.v1",
                "lifeos.ai.audit.hash.requested.v1",
                "{}",
                now);

        UUID lease = event.claim(now, Duration.ofSeconds(30));

        assertThatThrownBy(() -> event.claim(now, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        event.markPublished(lease, now.plusSeconds(1));
        assertThat(event.getPublishedAt()).isEqualTo(now.plusSeconds(1));
        assertThatThrownBy(() -> event.markPublished(lease, now.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedDeliveryIsRescheduledWithBoundedSafeCode() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        AiAuditHashOutboxEvent event = new AiAuditHashOutboxEvent(
                auditEvent(), "type", "topic", "{}", now);
        UUID lease = event.claim(now, Duration.ofSeconds(30));

        event.reschedule(lease, now.plusSeconds(2), "KAFKA_PUBLISH_FAILURE");

        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(2));
        assertThat(event.getPublishedAt()).isNull();
        UUID retryLease = event.claim(now.plusSeconds(2), Duration.ofSeconds(30));
        assertThatThrownBy(() -> event.reschedule(retryLease, now.plusSeconds(3), "unsafe-code"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AssistantRequestAuditEvent auditEvent() {
        AssistantAuditRecord record = new AssistantAuditRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                AssistantAuditOutcome.ALLOWED,
                "general",
                "bounded input",
                13,
                4,
                8,
                "NONE",
                "NONE",
                "provider",
                "model",
                "OUTPUT_RETURNED_ONCE",
                "bounded output",
                14,
                new BigDecimal("0.8000"),
                "NONE",
                "NOT_RUN",
                20,
                "correlation");
        return new AssistantRequestAuditEvent(record, null, null, CLIENT_DIGEST, AUDIT_DIGEST);
    }
}
