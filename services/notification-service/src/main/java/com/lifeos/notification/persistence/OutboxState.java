package com.lifeos.notification.persistence;

/** Relay state for an immutable notification-owned outbox event. */
public enum OutboxState {
    PENDING,
    IN_FLIGHT,
    PUBLISHED
}
