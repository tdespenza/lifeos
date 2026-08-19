package com.lifeos.documentvault.proof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable terminal failure record; the original immutable payload is retained for replay tooling. */
@Entity
@Table(name = "document_proof_outbox_dead_letter")
public class DocumentProofOutboxDeadLetter {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "outbox_event_id", nullable = false, updatable = false, unique = true)
    private UUID outboxEventId;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "failure_code", nullable = false, length = 64, updatable = false)
    private String failureCode;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "attempt_count", nullable = false, updatable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentProofOutboxDeadLetter() {
    }

    private DocumentProofOutboxDeadLetter(
            DocumentProofOutboxEvent event, String failureCode, Instant createdAt) {
        id = UUID.randomUUID();
        outboxEventId = Objects.requireNonNull(event.getId(), "event id must not be null");
        eventType = event.getEventType();
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
        payloadJson = event.getPayloadJson();
        attemptCount = event.getAttemptCount();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static DocumentProofOutboxDeadLetter from(
            DocumentProofOutboxEvent event, String failureCode, Instant createdAt) {
        return new DocumentProofOutboxDeadLetter(event, failureCode, createdAt);
    }
}
