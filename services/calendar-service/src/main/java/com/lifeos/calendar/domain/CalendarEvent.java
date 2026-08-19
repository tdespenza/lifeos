package com.lifeos.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped calendar event master; occurrences carry recurring scheduling instances. */
@Entity
@Table(name = "calendar_event")
public class CalendarEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "recurrence_rule", length = 128)
    private String recurrenceRule;

    @Column(name = "recurrence_revision", nullable = false)
    private long recurrenceRevision;

    @Column(name = "recurrence_next_materialization_at")
    private Instant recurrenceNextMaterializationAt;

    @Column(name = "origin_correlation_id", nullable = false)
    private UUID originCorrelationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalendarEventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CalendarEvent() {
    }

    private CalendarEvent(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String timeZone,
            String recurrenceRule,
            UUID originCorrelationId,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = requireText(tenantId, "tenantId", 255);
        assign(title, description, startAt, endAt, timeZone, recurrenceRule);
        recurrenceRevision = 0L;
        recurrenceNextMaterializationAt = this.recurrenceRule == null ? null : now;
        this.originCorrelationId = Objects.requireNonNull(originCorrelationId, "originCorrelationId must not be null");
        status = CalendarEventStatus.ACTIVE;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public static CalendarEvent active(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String timeZone,
            String recurrenceRule,
            UUID originCorrelationId,
            Instant now) {
        return new CalendarEvent(
                id,
                ownerAccountId,
                tenantId,
                title,
                description,
                startAt,
                endAt,
                timeZone,
                recurrenceRule,
                originCorrelationId,
                now);
    }

    /** Replaces event details and starts a new recurrence revision for future occurrences. */
    public void update(
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String timeZone,
            String recurrenceRule,
            UUID originCorrelationId,
            Instant now) {
        if (status != CalendarEventStatus.ACTIVE) {
            throw new CalendarLifecycleTransitionException("update");
        }
        assign(title, description, startAt, endAt, timeZone, recurrenceRule);
        recurrenceRevision++;
        recurrenceNextMaterializationAt = this.recurrenceRule == null ? null : now;
        this.originCorrelationId = Objects.requireNonNull(originCorrelationId, "originCorrelationId must not be null");
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Cancels remaining future work without deleting durable audit/idempotency history. */
    public void cancel(Instant now) {
        if (status == CalendarEventStatus.CANCELLED) {
            throw new CalendarLifecycleTransitionException("cancel");
        }
        status = CalendarEventStatus.CANCELLED;
        recurrenceNextMaterializationAt = null;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Defers the next bounded recurrence scan after the caller holds this series lock. */
    public void deferRecurrenceMaterialization(Instant nextMaterializationAt) {
        if (status != CalendarEventStatus.ACTIVE || recurrenceRule == null) {
            return;
        }
        recurrenceNextMaterializationAt = Objects.requireNonNull(nextMaterializationAt, "nextMaterializationAt must not be null");
    }

    public boolean isMaterializationDue(Instant now) {
        return status == CalendarEventStatus.ACTIVE
                && recurrenceRule != null
                && (recurrenceNextMaterializationAt == null || !recurrenceNextMaterializationAt.isAfter(now));
    }

    private void assign(
            String valueTitle,
            String valueDescription,
            Instant valueStartAt,
            Instant valueEndAt,
            String valueTimeZone,
            String valueRecurrenceRule) {
        title = requireText(valueTitle, "title", 140);
        description = optionalText(valueDescription, "description", 4_000);
        startAt = Objects.requireNonNull(valueStartAt, "startAt must not be null");
        endAt = Objects.requireNonNull(valueEndAt, "endAt must not be null");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        timeZone = requireTimeZone(valueTimeZone);
        recurrenceRule = optionalText(valueRecurrenceRule, "recurrenceRule", 128);
    }

    static String requireTimeZone(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("timeZone must be a bounded IANA zone identifier");
        }
        try {
            return ZoneId.of(value).getId();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZone must be a valid IANA zone identifier", exception);
        }
    }

    static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be nonblank and within its storage bound");
        }
        return value;
    }

    static String optionalText(String value, String field, int maximumLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be nonblank when supplied and within its storage bound");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
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

    public String getRecurrenceRule() {
        return recurrenceRule;
    }

    public long getRecurrenceRevision() {
        return recurrenceRevision;
    }

    public Instant getRecurrenceNextMaterializationAt() {
        return recurrenceNextMaterializationAt;
    }

    public UUID getOriginCorrelationId() {
        return originCorrelationId;
    }

    public CalendarEventStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }
}
