package com.lifeos.notification.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationProviderPayloadRendererTest {

    @Test
    void redactsEveryPushPreviewByDefaultIncludingSensitiveCategories() {
        ProviderNotificationPayload payload = NotificationProviderPayloadRenderer.render(request(NotificationChannel.PUSH));

        assertEquals("LifeOS notification", payload.title());
        assertEquals("Open LifeOS to view this notification.", payload.body());
        assertNull(payload.actionUri());
    }

    @Test
    void keepsBoundedContentForTheEmailProvider() {
        ProviderNotificationPayload payload = NotificationProviderPayloadRenderer.render(request(NotificationChannel.EMAIL));

        assertEquals("Finance balance changed", payload.title());
        assertEquals("A health and finance private detail", payload.body());
    }

    private static ProviderDeliveryRequest request(NotificationChannel channel) {
        return new ProviderDeliveryRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                channel,
                "destination",
                1,
                "finance.transaction",
                NotificationPriority.HIGH,
                "Finance balance changed",
                "A health and finance private detail",
                "lifeos://finance/secret",
                Instant.parse("2026-08-17T12:00:00Z"),
                null);
    }
}
