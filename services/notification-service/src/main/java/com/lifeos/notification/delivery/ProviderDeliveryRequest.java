package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import java.time.Instant;
import java.util.UUID;

/**
 * Plaintext destination is scoped to the provider call and deliberately redacted from diagnostic
 * string rendering. It must never be persisted in an outbox, dead letter, metric, or log.
 */
public record ProviderDeliveryRequest(
        UUID deliveryId,
        UUID notificationId,
        UUID sourceEventId,
        UUID recipientAccountId,
        NotificationChannel channel,
        String destination,
        long sequence,
        String category,
        NotificationPriority priority,
        String title,
        String body,
        String actionUri,
        Instant createdAt,
        Instant expiresAt) {

    public ProviderDeliveryRequest {
        if (deliveryId == null || notificationId == null || sourceEventId == null || recipientAccountId == null || channel == null) {
            throw new IllegalArgumentException("delivery identity fields must not be null");
        }
        if (destination != null && destination.isBlank()) {
            throw new IllegalArgumentException("destination must be null or nonblank");
        }
        if (sequence <= 0 || category == null || category.isBlank() || priority == null
                || title == null || title.isBlank() || body == null || body.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("notification content must not be blank");
        }
    }

    @Override
    public String toString() {
        return "ProviderDeliveryRequest[deliveryId=" + deliveryId + ", notificationId=" + notificationId
                + ", sourceEventId=" + sourceEventId
                + ", recipientAccountId=" + recipientAccountId + ", channel=" + channel
                + ", destination=[redacted], notification=[redacted]]";
    }
}
