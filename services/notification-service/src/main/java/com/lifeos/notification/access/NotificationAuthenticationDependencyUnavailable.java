package com.lifeos.notification.access;

/** Identity validation cannot complete safely, so notification APIs fail closed. */
public class NotificationAuthenticationDependencyUnavailable extends RuntimeException {

    public NotificationAuthenticationDependencyUnavailable() {
        super("authentication dependency unavailable");
    }

    public NotificationAuthenticationDependencyUnavailable(Throwable cause) {
        super("authentication dependency unavailable", cause);
    }
}
