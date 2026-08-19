package com.lifeos.identity.notification;

/** Publishes one already-persisted Identity notification outbox record. */
public interface IdentityNotificationEventPublisher {

    void publish(ClaimedIdentityNotificationOutboxEvent event);
}
