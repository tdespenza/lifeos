package com.lifeos.notification.messaging;

import java.util.UUID;

/** Result of accepting one Kafka-delivered CloudEvent into the durable local inbox. */
public record NotificationIngressResult(UUID notificationId, boolean duplicate) {
}
