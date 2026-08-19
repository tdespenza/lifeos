package com.lifeos.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Per-owner lock row serializing conflicting schedule writes without a distributed cache lock. */
@Entity
@Table(name = "calendar_schedule_lock")
public class CalendarScheduleLock {

    @Id
    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CalendarScheduleLock() {
    }

    private CalendarScheduleLock(UUID ownerAccountId, String tenantId, Instant createdAt) {
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = CalendarEvent.requireText(tenantId, "tenantId", 255);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static CalendarScheduleLock forOwner(UUID ownerAccountId, String tenantId, Instant createdAt) {
        return new CalendarScheduleLock(ownerAccountId, tenantId, createdAt);
    }

    public String getTenantId() {
        return tenantId;
    }
}
