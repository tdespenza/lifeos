package com.lifeos.notification.messaging;

/** Safe signal that the relay should retain and reschedule its immutable outbox row. */
public class EventBusPublishException extends RuntimeException {

    public EventBusPublishException() {
        super("event bus publish failed");
    }
}
