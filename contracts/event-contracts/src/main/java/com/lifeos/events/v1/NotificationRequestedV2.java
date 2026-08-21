package com.lifeos.events.v1;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * Version 2 notification request with an explicit IANA event time zone.
 *
 * <p>The added field deliberately requires a new topic and CloudEvents type rather than changing
 * {@link NotificationRequestedV1}. Notification-service validates and persists the V1-compatible
 * delivery fields plus this required source time-zone fact without reinterpreting V1 records.
 *
 * @param notificationId producer-stable logical notification identifier
 * @param recipientAccountId LifeOS account that owns resolved delivery endpoints
 * @param tenantId personal tenant asserted by the producer
 * @param category bounded producer-defined category
 * @param priority user-visible urgency
 * @param title short, privacy-reviewed notification title
 * @param body bounded, privacy-reviewed notification body
 * @param actionUri optional safe action target
 * @param requestedChannels independently deliverable channels
 * @param expiresAt optional delivery cutoff
 * @param eventTimeZone required IANA time zone that governed due-time calculation
 */
public record NotificationRequestedV2(
        UUID notificationId,
        UUID recipientAccountId,
        String tenantId,
        String category,
        NotificationPriority priority,
        String title,
        String body,
        URI actionUri,
        Set<NotificationChannel> requestedChannels,
        Instant expiresAt,
        String eventTimeZone) {

    private static final Set<String> AVAILABLE_ZONE_IDS = Set.copyOf(ZoneId.getAvailableZoneIds());

    /** Creates an immutable, validated V2 request. */
    public NotificationRequestedV2 {
        NotificationRequestedV1 compatible = new NotificationRequestedV1(
                notificationId,
                recipientAccountId,
                tenantId,
                category,
                priority,
                title,
                body,
                actionUri,
                requestedChannels,
                expiresAt);
        notificationId = compatible.notificationId();
        recipientAccountId = compatible.recipientAccountId();
        tenantId = compatible.tenantId();
        category = compatible.category();
        priority = compatible.priority();
        title = compatible.title();
        body = compatible.body();
        actionUri = compatible.actionUri();
        requestedChannels = compatible.requestedChannels();
        expiresAt = compatible.expiresAt();
        eventTimeZone = validateTimeZone(eventTimeZone);
    }

    /** Returns the subset persisted by the existing notification delivery model. */
    public NotificationRequestedV1 asV1() {
        return new NotificationRequestedV1(
                notificationId,
                recipientAccountId,
                tenantId,
                category,
                priority,
                title,
                body,
                actionUri,
                requestedChannels,
                expiresAt);
    }

    private static String validateTimeZone(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("eventTimeZone must be a bounded IANA zone identifier");
        }
        try {
            if (!AVAILABLE_ZONE_IDS.contains(value)) {
                throw new IllegalArgumentException("eventTimeZone must be a valid IANA zone identifier");
            }
            return ZoneId.of(value).getId();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("eventTimeZone must be a valid IANA zone identifier", exception);
        }
    }
}
