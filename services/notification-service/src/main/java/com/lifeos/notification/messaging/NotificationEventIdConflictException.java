package com.lifeos.notification.messaging;

/** A producer attempted to reuse a CloudEvents ID for semantically different payload data. */
public class NotificationEventIdConflictException extends RuntimeException {

    public NotificationEventIdConflictException() {
        super("notification event ID conflicts with an existing inbox record");
    }
}
