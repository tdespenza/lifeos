package com.lifeos.notification.stream;

/** The bounded stream cannot safely replay or retain a gap; client must resync via REST. */
public class NotificationStreamResyncRequiredException extends RuntimeException {

    public NotificationStreamResyncRequiredException() {
        super("notification stream resynchronization is required");
    }
}
