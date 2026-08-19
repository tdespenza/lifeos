package com.lifeos.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable event written in the same database transaction as a notification state change. */
@Entity
@Table(name = "notification_outbox_event")
public class NotificationOutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 200, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 249, updatable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 255, updatable = false)
    private String partitionKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "headers_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String headersJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private OutboxState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected NotificationOutboxEvent() {
    }

    private NotificationOutboxEvent(
            UUID id,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String topic,
            String partitionKey,
            String payloadJson,
            String headersJson,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        this.aggregateVersion = aggregateVersion;
        this.eventType = requireBounded(eventType, "eventType", 200);
        this.topic = requireBounded(topic, "topic", 249);
        this.partitionKey = requireBounded(partitionKey, "partitionKey", 255);
        this.payloadJson = requireBounded(payloadJson, "payloadJson", 1_000_000);
        this.headersJson = requireBounded(headersJson, "headersJson", 20_000);
        this.state = OutboxState.PENDING;
        this.availableAt = Objects.requireNonNull(now, "now must not be null");
        this.createdAt = now;
    }

    public static NotificationOutboxEvent pending(
            UUID id,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String topic,
            String partitionKey,
            String payloadJson,
            String headersJson,
            Instant now) {
        return new NotificationOutboxEvent(
                id, aggregateId, aggregateVersion, eventType, topic, partitionKey, payloadJson, headersJson, now);
    }

    /** Claims due or abandoned relay work. The caller must hold a pessimistic row lock. */
    public UUID claim(Instant now, Duration leaseDuration) {
        boolean due = state == OutboxState.PENDING && !availableAt.isAfter(now);
        boolean abandoned = state == OutboxState.IN_FLIGHT
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
        if (!due && !abandoned) {
            throw new IllegalStateException("outbox event is not claimable");
        }
        if (attemptCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("outbox attempt count exhausted");
        }
        attemptCount++;
        state = OutboxState.IN_FLIGHT;
        leaseToken = UUID.randomUUID();
        leaseExpiresAt = now.plus(leaseDuration);
        return leaseToken;
    }

    public void markPublished(UUID expectedLeaseToken, Instant now) {
        requireLease(expectedLeaseToken);
        state = OutboxState.PUBLISHED;
        clearLease();
        publishedAt = Objects.requireNonNull(now, "now must not be null");
        lastErrorCode = null;
    }

    /** Never discards an outbox event: a broker failure is rescheduled with a bounded delay. */
    public void reschedule(UUID expectedLeaseToken, String errorCode, Instant nextAvailableAt) {
        requireLease(expectedLeaseToken);
        state = OutboxState.PENDING;
        clearLease();
        lastErrorCode = requireErrorCode(errorCode);
        availableAt = Objects.requireNonNull(nextAvailableAt, "nextAvailableAt must not be null");
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getHeadersJson() {
        return headersJson;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    private void requireLease(UUID expectedLeaseToken) {
        if (state != OutboxState.IN_FLIGHT || leaseToken == null || !leaseToken.equals(expectedLeaseToken)) {
            throw new IllegalStateException("outbox lease is no longer held by this relay worker");
        }
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    private static String requireBounded(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be nonblank and within its storage bound");
        }
        return value;
    }

    private static String requireErrorCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("error code must be bounded and safe");
        }
        return value;
    }
}
