package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;

/**
 * Applies the privacy-safe preview rule at the final push boundary. Push providers receive a
 * generic prompt by default, so document, finance, health, and any future sensitive category do
 * not leak through lock-screen previews. Email retains the event's bounded content.
 */
public final class NotificationProviderPayloadRenderer {

    private static final String PUSH_TITLE = "LifeOS notification";
    private static final String PUSH_BODY = "Open LifeOS to view this notification.";

    private NotificationProviderPayloadRenderer() {
    }

    public static ProviderNotificationPayload render(ProviderDeliveryRequest request) {
        if (request.channel() == NotificationChannel.PUSH) {
            return new ProviderNotificationPayload(request.destination(), PUSH_TITLE, PUSH_BODY, null);
        }
        return new ProviderNotificationPayload(request.destination(), request.title(), request.body(), request.actionUri());
    }
}
