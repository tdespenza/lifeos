package com.lifeos.notification.read;

import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.notification.persistence.NotificationRecord;
import java.time.Instant;
import java.util.UUID;

/** Recipient-safe public projection. It deliberately excludes delivery endpoint/provider internals. */
public record NotificationView(
        UUID id,
        long sequence,
        String category,
        NotificationPriority priority,
        String title,
        String body,
        String actionUri,
        Instant createdAt,
        Instant expiresAt) {

    public static NotificationView from(NotificationRecord record) {
        return new NotificationView(
                record.getId(),
                record.getSequenceNumber(),
                record.getCategory(),
                record.getPriority(),
                record.getTitle(),
                record.getBody(),
                record.getActionUri(),
                record.getCreatedAt(),
                record.getExpiresAt());
    }
}
