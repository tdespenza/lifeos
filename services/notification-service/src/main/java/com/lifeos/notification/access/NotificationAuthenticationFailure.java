package com.lifeos.notification.access;

/** Inbound bearer credential is missing, malformed, expired, or revoked. */
public class NotificationAuthenticationFailure extends RuntimeException {

    public NotificationAuthenticationFailure() {
        super("authentication required");
    }

    public NotificationAuthenticationFailure(Throwable cause) {
        super("authentication required", cause);
    }
}
