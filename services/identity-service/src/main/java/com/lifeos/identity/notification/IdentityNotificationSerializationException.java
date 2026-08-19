package com.lifeos.identity.notification;

/** Serialization failure before a recovery notification can enter the durable outbox. */
public class IdentityNotificationSerializationException extends RuntimeException {

    public IdentityNotificationSerializationException(Throwable cause) {
        super(cause);
    }
}
