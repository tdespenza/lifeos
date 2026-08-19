package com.lifeos.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped reservation that never moves overlapping calendar entries implicitly. */
@Entity
@Table(name = "calendar_time_block")
public class CalendarTimeBlock {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 16)
    private CalendarLinkType linkType;

    @Column(name = "linked_resource_id")
    private UUID linkedResourceId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalendarTimeBlockStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CalendarTimeBlock() {
    }

    private CalendarTimeBlock(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            CalendarLinkType linkType,
            UUID linkedResourceId,
            Instant startAt,
            Instant endAt,
            String timeZone,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = CalendarEvent.requireText(tenantId, "tenantId", 255);
        assign(linkType, linkedResourceId, startAt, endAt, timeZone);
        status = CalendarTimeBlockStatus.ACTIVE;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public static CalendarTimeBlock active(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            CalendarLinkType linkType,
            UUID linkedResourceId,
            Instant startAt,
            Instant endAt,
            String timeZone,
            Instant now) {
        return new CalendarTimeBlock(
                id, ownerAccountId, tenantId, linkType, linkedResourceId, startAt, endAt, timeZone, now);
    }

    public void update(
            CalendarLinkType valueLinkType,
            UUID valueLinkedResourceId,
            Instant valueStartAt,
            Instant valueEndAt,
            String valueTimeZone,
            Instant now) {
        if (status != CalendarTimeBlockStatus.ACTIVE) {
            throw new CalendarLifecycleTransitionException("update");
        }
        assign(valueLinkType, valueLinkedResourceId, valueStartAt, valueEndAt, valueTimeZone);
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void cancel(Instant now) {
        if (status == CalendarTimeBlockStatus.CANCELLED) {
            throw new CalendarLifecycleTransitionException("cancel");
        }
        status = CalendarTimeBlockStatus.CANCELLED;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private void assign(
            CalendarLinkType valueLinkType,
            UUID valueLinkedResourceId,
            Instant valueStartAt,
            Instant valueEndAt,
            String valueTimeZone) {
        linkType = Objects.requireNonNull(valueLinkType, "linkType must not be null");
        if (linkType == CalendarLinkType.FOCUS && valueLinkedResourceId != null) {
            throw new IllegalArgumentException("focus blocks must not name a linked resource");
        }
        if (linkType != CalendarLinkType.FOCUS && valueLinkedResourceId == null) {
            throw new IllegalArgumentException("task and goal blocks require a linked resource");
        }
        linkedResourceId = valueLinkedResourceId;
        startAt = Objects.requireNonNull(valueStartAt, "startAt must not be null");
        endAt = Objects.requireNonNull(valueEndAt, "endAt must not be null");
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        timeZone = CalendarEvent.requireTimeZone(valueTimeZone);
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

    public CalendarLinkType getLinkType() {
        return linkType;
    }

    public UUID getLinkedResourceId() {
        return linkedResourceId;
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

    public CalendarTimeBlockStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }
}
