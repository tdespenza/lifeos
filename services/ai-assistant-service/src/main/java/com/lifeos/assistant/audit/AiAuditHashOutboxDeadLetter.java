package com.lifeos.assistant.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable terminal record for an AI audit commitment that exhausted broker retries. */
@Entity
@Table(name = "ai_audit_hash_outbox_dead_letter")
public class AiAuditHashOutboxDeadLetter {

    @Id
    @Column(name = "outbox_event_id")
    private UUID outboxEventId;

    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    @Column(name = "attempt_count", nullable = false, updatable = false)
    private int attemptCount;

    @Column(name = "failure_code", nullable = false, length = 64, updatable = false)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiAuditHashOutboxDeadLetter() {
        // required by JPA
    }

    private AiAuditHashOutboxDeadLetter(AiAuditHashOutboxEvent event, String failureCode, Instant createdAt) {
        outboxEventId = Objects.requireNonNull(event.getId(), "outbox event id must not be null");
        auditEventId = event.getAuditEventId();
        attemptCount = event.getAttemptCount();
        this.failureCode = failureCode;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    static AiAuditHashOutboxDeadLetter from(AiAuditHashOutboxEvent event, String failureCode, Instant createdAt) {
        return new AiAuditHashOutboxDeadLetter(event, failureCode, createdAt);
    }
}
