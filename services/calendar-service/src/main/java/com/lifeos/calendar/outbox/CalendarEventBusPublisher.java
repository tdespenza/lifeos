package com.lifeos.calendar.outbox;

/** Outbound Kafka-compatible port; Calendar does not invoke notification-service synchronously. */
public interface CalendarEventBusPublisher {

    void publish(ClaimedCalendarOutboxEvent event);
}
