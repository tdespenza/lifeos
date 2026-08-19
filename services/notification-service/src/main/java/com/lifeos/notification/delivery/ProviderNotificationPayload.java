package com.lifeos.notification.delivery;

/** Rendered provider command; this value is only constructed at the outbound provider boundary. */
public record ProviderNotificationPayload(String destination, String title, String body, String actionUri) {

    @Override
    public String toString() {
        return "ProviderNotificationPayload[redacted]";
    }
}
