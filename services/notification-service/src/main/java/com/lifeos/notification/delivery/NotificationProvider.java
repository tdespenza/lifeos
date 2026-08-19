package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;

/** Channel adapter boundary. Implementations must use bounded deadlines and provider idempotency. */
public interface NotificationProvider {

    NotificationChannel channel();

    ProviderDeliveryResult deliver(ProviderDeliveryRequest request);
}
