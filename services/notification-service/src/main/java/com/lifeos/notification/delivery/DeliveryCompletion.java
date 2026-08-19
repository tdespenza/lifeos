package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationDeliveryOutcome;

/** Committed result for metrics; no user content or destination appears here. */
public record DeliveryCompletion(NotificationChannel channel, NotificationDeliveryOutcome outcome, String reasonCode) {
}
