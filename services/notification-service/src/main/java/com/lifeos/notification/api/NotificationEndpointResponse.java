package com.lifeos.notification.api;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.persistence.NotificationEndpoint;
import java.time.Instant;
import java.util.UUID;

/** Endpoint metadata never renders encrypted or decrypted destination material. */
public record NotificationEndpointResponse(
        UUID id,
        NotificationChannel channel,
        boolean enabled,
        String disabledReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static NotificationEndpointResponse from(NotificationEndpoint endpoint) {
        return new NotificationEndpointResponse(
                endpoint.getId(),
                endpoint.getChannel(),
                endpoint.isEnabled(),
                endpoint.getDisabledReason(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt(),
                endpoint.getVersion());
    }
}
