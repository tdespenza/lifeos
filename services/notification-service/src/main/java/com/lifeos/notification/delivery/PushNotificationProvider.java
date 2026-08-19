package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.config.NotificationProviderProperties;
import org.springframework.stereotype.Component;

/** Bounded push provider adapter that can securely disable a confirmed invalid device token. */
@Component
public class PushNotificationProvider implements NotificationProvider {

    private final HttpNotificationProviderClient client;
    private final NotificationProviderProperties properties;

    public PushNotificationProvider(HttpNotificationProviderClient client, NotificationProviderProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public ProviderDeliveryResult deliver(ProviderDeliveryRequest request) {
        return client.deliver(properties.getPush(), request, true);
    }
}
