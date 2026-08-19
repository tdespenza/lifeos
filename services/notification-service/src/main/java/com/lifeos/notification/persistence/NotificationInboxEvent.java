package com.lifeos.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable inbox/deduplication row keyed by the producer-controlled CloudEvents ID.
 *
 * <p>The uniqueness constraint is intentionally claimed and flushed before notification business
 * rows are written. A concurrent duplicate therefore cannot create a second notification even
 * when Kafka redelivers after an uncertain acknowledgement.
 */
@Entity
@Table(name = "notification_inbox_event")
public class NotificationInboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "source", nullable = false, length = 512, updatable = false)
    private String source;

    @Column(name = "event_type", nullable = false, length = 200, updatable = false)
    private String eventType;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private InboxEventState state;

    @Column(name = "notification_id")
    private UUID notificationId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected NotificationInboxEvent() {
    }

    private NotificationInboxEvent(
            UUID eventId, String source, String eventType, UUID correlationId, String payloadHash, Instant receivedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.source = requireText(source, "source");
        this.eventType = requireText(eventType, "eventType");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.payloadHash = requireDigest(payloadHash);
        this.state = InboxEventState.RECEIVED;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }

    public static NotificationInboxEvent received(
            UUID eventId, String source, String eventType, UUID correlationId, String payloadHash, Instant receivedAt) {
        return new NotificationInboxEvent(eventId, source, eventType, correlationId, payloadHash, receivedAt);
    }

    public void markProcessed(UUID value, Instant at) {
        if (state != InboxEventState.RECEIVED) {
            throw new IllegalStateException("inbox event is already processed");
        }
        notificationId = Objects.requireNonNull(value, "notificationId must not be null");
        processedAt = Objects.requireNonNull(at, "processedAt must not be null");
        state = InboxEventState.PROCESSED;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public InboxEventState getState() {
        return state;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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
