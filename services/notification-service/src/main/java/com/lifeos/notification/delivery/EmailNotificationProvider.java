package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.config.NotificationProviderProperties;
import org.springframework.stereotype.Component;

/** Bounded email provider adapter; external email address resolution stays endpoint-owned. */
@Component
public class EmailNotificationProvider implements NotificationProvider {

    private final HttpNotificationProviderClient client;
    private final NotificationProviderProperties properties;

    public EmailNotificationProvider(HttpNotificationProviderClient client, NotificationProviderProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public ProviderDeliveryResult deliver(ProviderDeliveryRequest request) {
        return client.deliver(properties.getEmail(), request, false);
    }
}
