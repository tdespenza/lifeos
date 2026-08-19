package com.lifeos.notification.delivery;

/** Provider result classification; no raw provider response data is carried beyond this boundary. */
public enum ProviderDeliveryOutcome {
    DELIVERED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    SKIPPED
}
