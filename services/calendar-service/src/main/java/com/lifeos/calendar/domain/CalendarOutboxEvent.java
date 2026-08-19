package com.lifeos.calendar.domain;

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

/** Immutable Kafka payload written atomically with Calendar's reminder state transition. */
@Entity
@Table(name = "calendar_outbox_event")
public class CalendarOutboxEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reminder_id", nullable = false, updatable = false)
    private UUID reminderId;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 200, updatable = false)
    private String eventType;

    @Column(nullable = false, length = 249, updatable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 255, updatable = false)
    private String partitionKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "headers_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String headersJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalendarOutboxState state;

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
    @Column(nullable = false)
    private long version;

    protected CalendarOutboxEvent() {
    }

    private CalendarOutboxEvent(
            UUID id,
            UUID reminderId,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String topic,
            String partitionKey,
            String payloadJson,
            String headersJson,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reminderId = Objects.requireNonNull(reminderId, "reminderId must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        this.aggregateVersion = aggregateVersion;
        this.eventType = requireText(eventType, "eventType", 200);
        this.topic = requireText(topic, "topic", 249);
        this.partitionKey = requireText(partitionKey, "partitionKey", 255);
        this.payloadJson = requireText(payloadJson, "payloadJson", 1_000_000);
        this.headersJson = requireText(headersJson, "headersJson", 20_000);
        state = CalendarOutboxState.PENDING;
        availableAt = Objects.requireNonNull(now, "now must not be null");
        createdAt = now;
    }

    public static CalendarOutboxEvent pending(
            UUID id,
            UUID reminderId,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String topic,
            String partitionKey,
            String payloadJson,
            String headersJson,
            Instant now) {
        return new CalendarOutboxEvent(
                id,
                reminderId,
                aggregateId,
                aggregateVersion,
                eventType,
                topic,
                partitionKey,
                payloadJson,
                headersJson,
                now);
    }

    /** Claims due or abandoned relay work while holding a pessimistic database row lock. */
    public UUID claim(Instant now, Duration leaseDuration) {
        boolean due = state == CalendarOutboxState.PENDING && !availableAt.isAfter(now);
        boolean abandoned = state == CalendarOutboxState.IN_FLIGHT
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
        if (!due && !abandoned) {
            throw new IllegalStateException("outbox event is not claimable");
        }
        attemptCount++;
        state = CalendarOutboxState.IN_FLIGHT;
        leaseToken = UUID.randomUUID();
        leaseExpiresAt = now.plus(requirePositive(leaseDuration));
        return leaseToken;
    }

    public void markPublished(UUID expectedLease, Instant now) {
        requireLease(expectedLease);
        state = CalendarOutboxState.PUBLISHED;
        clearLease();
        publishedAt = Objects.requireNonNull(now, "now must not be null");
        lastErrorCode = null;
    }

    public void reschedule(UUID expectedLease, Instant nextAvailableAt, String errorCode) {
        requireLease(expectedLease);
        state = CalendarOutboxState.PENDING;
        clearLease();
        availableAt = Objects.requireNonNull(nextAvailableAt, "nextAvailableAt must not be null");
        lastErrorCode = requireErrorCode(errorCode);
    }

    public void deadLetter(UUID expectedLease, String errorCode) {
        requireLease(expectedLease);
        state = CalendarOutboxState.DEAD_LETTER;
        clearLease();
        lastErrorCode = requireErrorCode(errorCode);
    }

    public void cancel() {
        if (state == CalendarOutboxState.PENDING) {
            state = CalendarOutboxState.CANCELLED;
        }
    }

    private void requireLease(UUID expectedLease) {
        if (state != CalendarOutboxState.IN_FLIGHT || leaseToken == null || !leaseToken.equals(expectedLease)) {
            throw new IllegalStateException("outbox lease is no longer held");
        }
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return value;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
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

    public UUID getId() {
        return id;
    }

    public UUID getReminderId() {
        return reminderId;
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

    public CalendarOutboxState getState() {
        return state;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }
}
