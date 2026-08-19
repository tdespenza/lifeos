package com.lifeos.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** Per-recipient sequence cursor used for resumable, ordered notification streams. */
@Entity
@Table(name = "notification_subject_sequence")
public class NotificationSubjectSequence {

    @Id
    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    protected NotificationSubjectSequence() {
    }

    private NotificationSubjectSequence(UUID recipientAccountId, long lastSequence) {
        this.recipientAccountId = Objects.requireNonNull(recipientAccountId, "recipientAccountId must not be null");
        this.lastSequence = lastSequence;
    }

    public static NotificationSubjectSequence startingAt(UUID recipientAccountId) {
        return new NotificationSubjectSequence(recipientAccountId, 0);
    }

    public long next() {
        if (lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("notification sequence exhausted");
        }
        return ++lastSequence;
    }
}
