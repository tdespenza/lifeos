package com.lifeos.notification.persistence;

import com.lifeos.events.v1.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable, privacy-minimized terminal record for provider failures. */
@Entity
@Table(name = "notification_dead_letter")
public class NotificationDeadLetter {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private NotificationChannel channel;

    @Column(name = "attempts", nullable = false, updatable = false)
    private int attempts;

    @Column(name = "reason_code", nullable = false, length = 80, updatable = false)
    private String reasonCode;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "last_replayed_at")
    private Instant lastReplayedAt;

    protected NotificationDeadLetter() {
    }

    private NotificationDeadLetter(NotificationDelivery delivery, String reasonCode, String payloadHash, Instant now) {
        this.id = UUID.randomUUID();
        this.deliveryId = delivery.getId();
        this.sourceEventId = delivery.getSourceEventId();
        this.recipientAccountId = delivery.getRecipientAccountId();
        this.channel = delivery.getChannel();
        this.attempts = delivery.getAttemptCount();
        this.reasonCode = requireReason(reasonCode);
        this.payloadHash = requireDigest(payloadHash);
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static NotificationDeadLetter from(
            NotificationDelivery delivery, String reasonCode, String payloadHash, Instant now) {
        return new NotificationDeadLetter(delivery, reasonCode, payloadHash, now);
    }

    private static String requireReason(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("reason code must be bounded and safe");
        }
        return value;
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadHash must be a SHA-256 hex digest");
        }
        return value;
    }
}
