package com.lifeos.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Bounded materialized instance of an event series used for reminders and conflict checks. */
@Entity
@Table(name = "calendar_occurrence")
public class CalendarOccurrence {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "recurrence_revision", nullable = false, updatable = false)
    private long recurrenceRevision;

    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false, updatable = false)
    private Instant endAt;

    @Column(name = "time_zone", nullable = false, length = 64, updatable = false)
    private String timeZone;

    @Column(name = "origin_correlation_id", nullable = false, updatable = false)
    private UUID originCorrelationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalendarOccurrenceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CalendarOccurrence() {
    }

    private CalendarOccurrence(
            UUID id,
            CalendarEvent event,
            long recurrenceRevision,
            Instant startAt,
            Instant endAt,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        eventId = event.getId();
        ownerAccountId = event.getOwnerAccountId();
        tenantId = event.getTenantId();
        if (recurrenceRevision < 0) {
            throw new IllegalArgumentException("recurrenceRevision must not be negative");
        }
        this.recurrenceRevision = recurrenceRevision;
        this.startAt = Objects.requireNonNull(startAt, "startAt must not be null");
        this.endAt = Objects.requireNonNull(endAt, "endAt must not be null");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        timeZone = event.getTimeZone();
        originCorrelationId = event.getOriginCorrelationId();
        status = CalendarOccurrenceStatus.ACTIVE;
        createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static CalendarOccurrence active(
            UUID id, CalendarEvent event, long recurrenceRevision, Instant startAt, Instant endAt, Instant now) {
        return new CalendarOccurrence(id, event, recurrenceRevision, startAt, endAt, now);
    }

    public void cancel() {
        status = CalendarOccurrenceStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getRecurrenceRevision() {
        return recurrenceRevision;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public UUID getOriginCorrelationId() {
        return originCorrelationId;
    }

    public CalendarOccurrenceStatus getStatus() {
        return status;
    }
}
