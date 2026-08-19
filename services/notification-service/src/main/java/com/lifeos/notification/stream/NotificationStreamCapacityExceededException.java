package com.lifeos.notification.stream;

/** Per-account bounded concurrent stream limit is exhausted. */
public class NotificationStreamCapacityExceededException extends RuntimeException {

    public NotificationStreamCapacityExceededException() {
        super("notification stream capacity is temporarily exhausted");
    }
}
