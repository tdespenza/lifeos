package com.lifeos.notification.persistence;

/** Lifecycle for one independently retried notification channel/endpoint delivery. */
public enum DeliveryState {
    PENDING,
    IN_FLIGHT,
    RETRY_SCHEDULED,
    DELIVERED,
    SKIPPED,
    DEAD_LETTERED
}
