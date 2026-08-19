package com.lifeos.identity.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Durable, redacted terminal state for a recovery notification that exhausted relay attempts. */
@Entity
@Table(name = "identity_notification_outbox_dead_letter")
public class IdentityNotificationOutboxDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "outbox_event_id", nullable = false, unique = true, updatable = false)
    private UUID outboxEventId;

    @Column(name = "attempt_count", nullable = false, updatable = false)
    private int attemptCount;

    @Column(name = "error_code", nullable = false, length = 80, updatable = false)
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdentityNotificationOutboxDeadLetter() {
        // required by JPA
    }

    private IdentityNotificationOutboxDeadLetter(
            UUID outboxEventId, int attemptCount, String errorCode, Instant createdAt) {
        this.outboxEventId = outboxEventId;
        this.attemptCount = attemptCount;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
    }

    public static IdentityNotificationOutboxDeadLetter from(
            IdentityNotificationOutboxEvent event, String errorCode, Instant now) {
        return new IdentityNotificationOutboxDeadLetter(
                event.getId(), event.getAttemptCount(), errorCode, now);
    }

    public UUID getOutboxEventId() {
        return outboxEventId;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
