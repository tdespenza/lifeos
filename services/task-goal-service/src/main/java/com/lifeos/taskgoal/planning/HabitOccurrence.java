package com.lifeos.taskgoal.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable occurrence event keyed by local habit date. */
@Entity
@Table(name = "habit_occurrence", indexes = @Index(name = "idx_habit_occurrence_habit_date", columnList = "habit_id, occurrence_date"))
public class HabitOccurrence {

    @Id
    private UUID id;

    @Column(name = "habit_id", nullable = false, updatable = false)
    private UUID habitId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "occurrence_date", nullable = false, updatable = false)
    private LocalDate occurrenceDate;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected HabitOccurrence() {
    }

    public HabitOccurrence(UUID id, UUID habitId, UUID ownerAccountId, String tenantId, LocalDate occurrenceDate) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.habitId = Objects.requireNonNull(habitId, "habitId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.occurrenceDate = Objects.requireNonNull(occurrenceDate, "occurrenceDate must not be null");
        this.occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHabitId() { return habitId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public LocalDate getOccurrenceDate() { return occurrenceDate; }
    public Instant getOccurredAt() { return occurredAt; }
}
