package com.lifeos.identity.notification;

/** Failure publishing an already durable Identity notification event. */
public class IdentityNotificationPublishException extends RuntimeException {

    public IdentityNotificationPublishException(Throwable cause) {
        super(cause);
    }
}
