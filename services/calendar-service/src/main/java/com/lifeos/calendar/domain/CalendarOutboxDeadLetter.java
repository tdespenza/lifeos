package com.lifeos.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable producer-side dead-letter fact; payload remains in the immutable outbox row. */
@Entity
@Table(name = "calendar_outbox_dead_letter")
public class CalendarOutboxDeadLetter {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "outbox_event_id", nullable = false, updatable = false, unique = true)
    private UUID outboxEventId;

    @Column(name = "attempt_count", nullable = false, updatable = false)
    private int attemptCount;

    @Column(name = "error_code", nullable = false, length = 80, updatable = false)
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CalendarOutboxDeadLetter() {
    }

    private CalendarOutboxDeadLetter(UUID outboxEventId, int attemptCount, String errorCode, Instant createdAt) {
        id = UUID.randomUUID();
        this.outboxEventId = Objects.requireNonNull(outboxEventId, "outboxEventId must not be null");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        this.attemptCount = attemptCount;
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("errorCode must be bounded and safe");
        }
        this.errorCode = errorCode;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static CalendarOutboxDeadLetter from(CalendarOutboxEvent event, String errorCode, Instant now) {
        return new CalendarOutboxDeadLetter(event.getId(), event.getAttemptCount(), errorCode, now);
    }
}
