package com.lifeos.identity.notification;

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

/** Immutable notification payload with a lease-safe, at-least-once relay lifecycle. */
@Entity
@Table(name = "identity_notification_outbox_event")
public class IdentityNotificationOutboxEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 249, updatable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 255, updatable = false)
    private String partitionKey;

    @Column(name = "event_type", nullable = false, length = 200, updatable = false)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "headers_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String headersJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdentityNotificationOutboxState state;

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

    protected IdentityNotificationOutboxEvent() {
        // required by JPA
    }

    private IdentityNotificationOutboxEvent(
            UUID id,
            String topic,
            String partitionKey,
            String eventType,
            String payloadJson,
            String headersJson,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.topic = requireText(topic, "topic", 249);
        this.partitionKey = requireText(partitionKey, "partitionKey", 255);
        this.eventType = requireText(eventType, "eventType", 200);
        this.payloadJson = requireText(payloadJson, "payloadJson", 1_000_000);
        this.headersJson = requireText(headersJson, "headersJson", 20_000);
        this.state = IdentityNotificationOutboxState.PENDING;
        this.attemptCount = 0;
        this.availableAt = Objects.requireNonNull(now, "now must not be null");
        this.createdAt = now;
    }

    public static IdentityNotificationOutboxEvent pending(
            UUID id,
            String topic,
            String partitionKey,
            String eventType,
            String payloadJson,
            String headersJson,
            Instant now) {
        return new IdentityNotificationOutboxEvent(
                id, topic, partitionKey, eventType, payloadJson, headersJson, now);
    }

    public UUID claim(Instant now, Duration leaseDuration) {
        boolean due = state == IdentityNotificationOutboxState.PENDING && !availableAt.isAfter(now);
        boolean abandoned = state == IdentityNotificationOutboxState.IN_FLIGHT
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
        if (!due && !abandoned) {
            throw new IllegalStateException("identity notification outbox event is not claimable");
        }
        attemptCount++;
        state = IdentityNotificationOutboxState.IN_FLIGHT;
        leaseToken = UUID.randomUUID();
        leaseExpiresAt = now.plus(requirePositive(leaseDuration));
        return leaseToken;
    }

    public void markPublished(UUID expectedLease, Instant now) {
        requireLease(expectedLease);
        state = IdentityNotificationOutboxState.PUBLISHED;
        clearLease();
        publishedAt = Objects.requireNonNull(now, "now must not be null");
        lastErrorCode = null;
    }

    public void reschedule(UUID expectedLease, Instant nextAvailableAt, String errorCode) {
        requireLease(expectedLease);
        state = IdentityNotificationOutboxState.PENDING;
        clearLease();
        availableAt = Objects.requireNonNull(nextAvailableAt, "nextAvailableAt must not be null");
        lastErrorCode = requireErrorCode(errorCode);
    }

    public void deadLetter(UUID expectedLease, String errorCode) {
        requireLease(expectedLease);
        state = IdentityNotificationOutboxState.DEAD_LETTER;
        clearLease();
        lastErrorCode = requireErrorCode(errorCode);
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getHeadersJson() {
        return headersJson;
    }

    public IdentityNotificationOutboxState getState() {
        return state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    private void requireLease(UUID expectedLease) {
        if (state != IdentityNotificationOutboxState.IN_FLIGHT
                || leaseToken == null
                || !leaseToken.equals(expectedLease)) {
            throw new IllegalStateException("identity notification outbox lease is no longer held");
        }
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }

    private static String requireErrorCode(String value) {
        return requireText(value, "errorCode", 80).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        return value;
    }
}
