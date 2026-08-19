package com.lifeos.notification.persistence;

import com.lifeos.events.v1.NotificationChannel;
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

/**
 * Independently retryable channel/endpoint work item. External provider calls occur only after a
 * short lease transaction commits, so a slow or unavailable provider never holds database locks.
 */
@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private NotificationChannel channel;

    @Column(name = "endpoint_id", updatable = false)
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private DeliveryState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_reason_code", length = 80)
    private String lastReasonCode;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected NotificationDelivery() {
    }

    private NotificationDelivery(
            UUID notificationId,
            UUID sourceEventId,
            UUID recipientAccountId,
            NotificationChannel channel,
            UUID endpointId,
            Instant now) {
        this.id = UUID.randomUUID();
        this.notificationId = Objects.requireNonNull(notificationId, "notificationId must not be null");
        this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        this.recipientAccountId = Objects.requireNonNull(recipientAccountId, "recipientAccountId must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        if (channel == NotificationChannel.REALTIME && endpointId != null) {
            throw new IllegalArgumentException("realtime delivery must not have a stored endpoint");
        }
        this.endpointId = endpointId;
        this.state = DeliveryState.PENDING;
        this.nextAttemptAt = Objects.requireNonNull(now, "now must not be null");
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static NotificationDelivery pending(
            UUID notificationId,
            UUID sourceEventId,
            UUID recipientAccountId,
            NotificationChannel channel,
            UUID endpointId,
            Instant now) {
        return new NotificationDelivery(notificationId, sourceEventId, recipientAccountId, channel, endpointId, now);
    }

    /** Claims due or abandoned work. The caller must hold a pessimistic row lock. */
    public UUID claim(Instant now, Duration leaseDuration) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        boolean due = (state == DeliveryState.PENDING || state == DeliveryState.RETRY_SCHEDULED)
                && !nextAttemptAt.isAfter(now);
        boolean abandoned = state == DeliveryState.IN_FLIGHT
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
        if (!due && !abandoned) {
            throw new IllegalStateException("delivery is not claimable");
        }
        if (attemptCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("delivery attempt count exhausted");
        }
        attemptCount++;
        state = DeliveryState.IN_FLIGHT;
        leaseToken = UUID.randomUUID();
        leaseExpiresAt = now.plus(leaseDuration);
        updatedAt = now;
        return leaseToken;
    }

    public void markDelivered(UUID expectedLeaseToken, String messageId, Instant now) {
        requireCurrentLease(expectedLeaseToken);
        state = DeliveryState.DELIVERED;
        clearLease();
        providerMessageId = boundedNullable(messageId, 255);
        lastReasonCode = "DELIVERED";
        deliveredAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public void scheduleRetry(UUID expectedLeaseToken, String reasonCode, Instant nextAttempt, Instant now) {
        requireCurrentLease(expectedLeaseToken);
        state = DeliveryState.RETRY_SCHEDULED;
        clearLease();
        lastReasonCode = requireReason(reasonCode);
        nextAttemptAt = Objects.requireNonNull(nextAttempt, "nextAttempt must not be null");
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void markSkipped(UUID expectedLeaseToken, String reasonCode, Instant now) {
        requireCurrentLease(expectedLeaseToken);
        state = DeliveryState.SKIPPED;
        clearLease();
        lastReasonCode = requireReason(reasonCode);
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void markDeadLettered(UUID expectedLeaseToken, String reasonCode, Instant now) {
        requireCurrentLease(expectedLeaseToken);
        state = DeliveryState.DEAD_LETTERED;
        clearLease();
        lastReasonCode = requireReason(reasonCode);
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public UUID getRecipientAccountId() {
        return recipientAccountId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public DeliveryState getState() {
        return state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public String getLastReasonCode() {
        return lastReasonCode;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    private void requireCurrentLease(UUID expectedLeaseToken) {
        if (state != DeliveryState.IN_FLIGHT || leaseToken == null || !leaseToken.equals(expectedLeaseToken)) {
            throw new IllegalStateException("delivery lease is no longer held by this worker");
        }
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    private static String requireReason(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("reason code must contain only uppercase letters, digits, and underscores");
        }
        return value;
    }

    private static String boundedNullable(String value, int maximumLength) {
        if (value != null && (value.isBlank() || value.length() > maximumLength)) {
            throw new IllegalArgumentException("provider message ID is blank or exceeds its storage bound");
        }
        return value;
    }
}
