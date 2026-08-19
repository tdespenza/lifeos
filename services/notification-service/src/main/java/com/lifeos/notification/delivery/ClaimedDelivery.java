package com.lifeos.notification.delivery;

import java.util.UUID;

/** Detached lease identity returned after a short claim transaction commits. */
public record ClaimedDelivery(UUID deliveryId, UUID leaseToken) {
}
