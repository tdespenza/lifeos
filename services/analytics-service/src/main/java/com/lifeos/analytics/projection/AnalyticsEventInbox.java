package com.lifeos.analytics.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Durable event-id dedupe record for at-least-once projection consumption. */
@Entity
@Table(name = "analytics_event_inbox")
public class AnalyticsEventInbox {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AnalyticsEventInbox() {}

    public AnalyticsEventInbox(UUID eventId, String eventType, Instant receivedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }

    public UUID getEventId() {
        return eventId;
    }
}
