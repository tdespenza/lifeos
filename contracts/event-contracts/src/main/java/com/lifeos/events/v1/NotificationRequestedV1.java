package com.lifeos.events.v1;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Version 1 payload for a request to notify one LifeOS account.
 *
 * <p>It deliberately identifies a recipient account rather than an email address, device token,
 * or SSE connection. Notification-service resolves and protects channel destinations locally so
 * Kafka records and producer logs do not spread delivery credentials or contact details.
 *
 * @param notificationId producer-stable logical notification identifier
 * @param recipientAccountId LifeOS account that owns all resolved delivery endpoints
 * @param tenantId tenant scope asserted by the producer
 * @param category bounded producer-defined category used for client rendering and preferences
 * @param priority user-visible urgency
 * @param title short notification title
 * @param body bounded body text
 * @param actionUri optional HTTPS or hierarchical {@code lifeos://} action target
 * @param requestedChannels nonempty independently deliverable channels
 * @param expiresAt optional absolute time after which delivery is skipped
 */
public record NotificationRequestedV1(
        UUID notificationId,
        UUID recipientAccountId,
        String tenantId,
        String category,
        NotificationPriority priority,
        String title,
        String body,
        URI actionUri,
        Set<NotificationChannel> requestedChannels,
        Instant expiresAt) {

    private static final int MAX_TENANT_LENGTH = 255;
    private static final int MAX_CATEGORY_LENGTH = 64;
    private static final int MAX_TITLE_LENGTH = 140;
    private static final int MAX_BODY_LENGTH = 4_000;
    private static final int MAX_ACTION_URI_LENGTH = 2_048;

    public NotificationRequestedV1 {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(recipientAccountId, "recipientAccountId must not be null");
        EventText.requireText(tenantId, "tenantId", MAX_TENANT_LENGTH);
        EventText.requireToken(category, "category", MAX_CATEGORY_LENGTH);
        Objects.requireNonNull(priority, "priority must not be null");
        EventText.requireText(title, "title", MAX_TITLE_LENGTH);
        EventText.requireText(body, "body", MAX_BODY_LENGTH);
        validateActionUri(actionUri);
        requestedChannels = immutableChannels(requestedChannels);
    }

    private static Set<NotificationChannel> immutableChannels(Set<NotificationChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("requestedChannels must not be empty");
        }
        if (channels.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("requestedChannels must not contain null");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(channels));
    }

    private static void validateActionUri(URI value) {
        if (value == null) {
            return;
        }
        if (value.toASCIIString().length() > MAX_ACTION_URI_LENGTH) {
            throw new IllegalArgumentException("actionUri must not exceed 2048 characters");
        }
        String scheme = value.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean validHttps = https && value.isAbsolute() && !value.isOpaque()
                && value.getHost() != null && !value.getHost().isBlank();
        boolean validLifeos = "lifeos".equalsIgnoreCase(scheme) && value.isAbsolute() && !value.isOpaque();
        if (value.getRawUserInfo() != null || value.getRawQuery() != null
                || value.getRawFragment() != null
                || !(validHttps || validLifeos)) {
            throw new IllegalArgumentException(
                    "actionUri must be an https or lifeos URI without user info, query, or fragment");
        }
    }
}
