package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * Realtime delivery is durable because the notification record already committed. The committed
 * delivery-status outbox event is consumed by every service instance's unique local stream group,
 * rather than directly publishing only to sessions attached to this JVM.
 */
@Component
public class RealtimeNotificationProvider implements NotificationProvider {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.REALTIME;
    }

    @Override
    public ProviderDeliveryResult deliver(ProviderDeliveryRequest request) {
        return ProviderDeliveryResult.delivered(null);
    }
}
