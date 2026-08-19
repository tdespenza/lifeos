package com.lifeos.taskgoal.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Versioned ordered routine definition with a bounded recurrence cadence. */
@Entity
@Table(name = "routine", indexes = @Index(name = "idx_routine_owner_tenant", columnList = "owner_account_id, tenant_id"))
public class Routine {

    @Id
    private UUID id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoutineCadence cadence;
    @Column(name = "activities_json", nullable = false, columnDefinition = "TEXT")
    private String activitiesJson;
    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;
    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected Routine() {
    }

    public Routine(UUID id, String name, String timeZone, RoutineCadence cadence, List<String> activities,
            UUID ownerAccountId, String tenantId, ObjectMapper mapper) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = bounded(name, 120, "name");
        this.timeZone = validZone(timeZone);
        this.cadence = Objects.requireNonNull(cadence, "cadence must not be null");
        this.activitiesJson = serializeActivities(activities, mapper);
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = bounded(tenantId, 255, "tenantId");
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getTimeZone() { return timeZone; }
    public RoutineCadence getCadence() { return cadence; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public long getVersion() { return version; }

    public List<String> activities(ObjectMapper mapper) {
        try {
            return mapper.readValue(activitiesJson, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("routine activities are corrupt", exception);
        }
    }

    public void update(String name, String timeZone, RoutineCadence cadence, List<String> activities, ObjectMapper mapper) {
        this.name = bounded(name, 120, "name");
        this.timeZone = validZone(timeZone);
        this.cadence = Objects.requireNonNull(cadence, "cadence must not be null");
        this.activitiesJson = serializeActivities(activities, mapper);
        this.updatedAt = Instant.now();
    }

    private static String serializeActivities(List<String> values, ObjectMapper mapper) {
        if (values == null || values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException("activities must contain 1 to 32 items");
        }
        List<String> copy = values.stream().map(value -> bounded(value, 120, "activity")).toList();
        try {
            return mapper.writeValueAsString(copy);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("activities could not be encoded", exception);
        }
    }

    private static String bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return value.trim();
    }

    private static String validZone(String value) {
        String zone = bounded(value, 64, "timeZone");
        ZoneId.of(zone);
        return zone;
    }
}
