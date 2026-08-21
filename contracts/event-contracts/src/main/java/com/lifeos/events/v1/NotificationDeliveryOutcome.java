package com.lifeos.events.v1;

/** Terminal or observable progress outcome for one notification channel. */
public enum NotificationDeliveryOutcome {
    DELIVERED,
    RETRY_SCHEDULED,
    SKIPPED,
    DEAD_LETTERED
}
