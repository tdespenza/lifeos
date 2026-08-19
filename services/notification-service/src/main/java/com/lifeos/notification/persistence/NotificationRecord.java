package com.lifeos.notification.persistence;

import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV1;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable, recipient-owned notification content used by list and resumable realtime reads. */
@Entity
@Table(name = "notification_record")
public class NotificationRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "category", nullable = false, length = 64, updatable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16, updatable = false)
    private NotificationPriority priority;

    @Column(name = "title", nullable = false, length = 140, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, length = 4000, updatable = false)
    private String body;

    @Column(name = "action_uri", length = 2048, updatable = false)
    private String actionUri;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "event_time_zone", length = 64, updatable = false)
    private String eventTimeZone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationRecord() {
    }

    private NotificationRecord(
            UUID sourceEventId,
            UUID correlationId,
            NotificationRequestedV1 request,
            long sequenceNumber,
            String eventTimeZone,
            Instant createdAt) {
        this.id = Objects.requireNonNull(request.notificationId(), "notificationId must not be null");
        this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.recipientAccountId = request.recipientAccountId();
        this.tenantId = request.tenantId();
        this.sequenceNumber = sequenceNumber;
        this.category = request.category();
        this.priority = request.priority();
        this.title = request.title();
        this.body = request.body();
        this.actionUri = request.actionUri() == null ? null : request.actionUri().toString();
        this.expiresAt = request.expiresAt();
        this.eventTimeZone = optionalTimeZone(eventTimeZone);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static NotificationRecord from(
            UUID sourceEventId,
            UUID correlationId,
            NotificationRequestedV1 request,
            long sequenceNumber,
            Instant createdAt) {
        return from(sourceEventId, correlationId, request, sequenceNumber, null, createdAt);
    }

    /** Persists the additive V2 event time-zone fact without changing V1's representation. */
    public static NotificationRecord from(
            UUID sourceEventId,
            UUID correlationId,
            NotificationRequestedV1 request,
            long sequenceNumber,
            String eventTimeZone,
            Instant createdAt) {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        return new NotificationRecord(sourceEventId, correlationId, request, sequenceNumber, eventTimeZone, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public UUID getRecipientAccountId() {
        return recipientAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getCategory() {
        return category;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getActionUri() {
        return actionUri;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getEventTimeZone() {
        return eventTimeZone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static String optionalTimeZone(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("eventTimeZone must be bounded when supplied");
        }
        return value;
    }
}
