package com.lifeos.identity.notification;

/** Durable lifecycle of one Identity-produced notification command. */
public enum IdentityNotificationOutboxState {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    DEAD_LETTER,
    CANCELLED
}
