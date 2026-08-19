package com.lifeos.notification.api;

import com.lifeos.events.v1.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Public endpoint enrollment input. Realtime intentionally has no contact destination. */
public record RegisterNotificationEndpointRequest(@NotNull NotificationChannel channel, @NotBlank String destination) {
}
