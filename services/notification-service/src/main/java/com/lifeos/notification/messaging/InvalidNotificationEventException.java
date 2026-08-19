package com.lifeos.notification.messaging;

/** An inbound record is structurally valid JSON but violates the versioned notification contract. */
public class InvalidNotificationEventException extends RuntimeException {

    public InvalidNotificationEventException(String message) {
        super(message);
    }

    public InvalidNotificationEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
