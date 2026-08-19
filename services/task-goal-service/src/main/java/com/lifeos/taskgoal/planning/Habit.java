package com.lifeos.taskgoal.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Owner-scoped habit definition; occurrence history is immutable and separately persisted. */
@Entity
@Table(name = "habit", indexes = @Index(name = "idx_habit_owner_tenant", columnList = "owner_account_id, tenant_id"))
public class Habit {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HabitCadence cadence;

    @Column(nullable = false, length = 64)
    private String timeZone;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Habit() {
    }

    public Habit(UUID id, String name, HabitCadence cadence, String timeZone, UUID ownerAccountId, String tenantId) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = bounded(name, 120, "name");
        this.cadence = Objects.requireNonNull(cadence, "cadence must not be null");
        this.timeZone = validZone(timeZone);
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = bounded(tenantId, 255, "tenantId");
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public HabitCadence getCadence() { return cadence; }
    public String getTimeZone() { return timeZone; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void update(String name, HabitCadence cadence, String timeZone) {
        if (!active) {
            throw new IllegalStateException("inactive habit cannot be updated");
        }
        this.name = bounded(name, 120, "name");
        this.cadence = Objects.requireNonNull(cadence, "cadence must not be null");
        this.timeZone = validZone(timeZone);
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        active = false;
        updatedAt = Instant.now();
    }

    private static String bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return value.trim();
    }

    private static String validZone(String value) {
        String zone = bounded(value, 64, "timeZone");
        try {
            ZoneId.of(zone);
            return zone;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZone must be an IANA zone", exception);
        }
    }
}
