package com.lifeos.notification.messaging;

/**
 * Broker adapter used exclusively by the outbox relay. A successful return means the broker
 * acknowledged the record according to its configured durability semantics.
 */
public interface EventBusPublisher {

    void publish(ClaimedOutboxEvent event);
}
