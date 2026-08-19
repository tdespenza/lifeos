package com.lifeos.taskgoal.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Durable idempotent routine materialization marker. */
@Entity
@Table(name = "routine_occurrence", indexes = @Index(name = "idx_routine_occurrence_scope", columnList = "routine_id, occurrence_date"))
public class RoutineOccurrence {
    @Id
    private UUID id;
    @Column(name = "routine_id", nullable = false, updatable = false)
    private UUID routineId;
    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;
    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;
    @Column(name = "occurrence_date", nullable = false, updatable = false)
    private LocalDate occurrenceDate;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoutineOccurrence() {
    }

    public RoutineOccurrence(UUID id, UUID routineId, UUID ownerAccountId, String tenantId, LocalDate occurrenceDate) {
        this.id = id;
        this.routineId = routineId;
        this.ownerAccountId = ownerAccountId;
        this.tenantId = tenantId;
        this.occurrenceDate = occurrenceDate;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRoutineId() { return routineId; }
    public UUID getOwnerAccountId() { return ownerAccountId; }
    public String getTenantId() { return tenantId; }
    public LocalDate getOccurrenceDate() { return occurrenceDate; }
}
