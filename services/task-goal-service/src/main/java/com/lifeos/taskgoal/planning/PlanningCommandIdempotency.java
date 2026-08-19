package com.lifeos.taskgoal.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Durable exact-response replay record for planning mutations. */
@Entity
@Table(name = "planning_command_idempotency", uniqueConstraints = @UniqueConstraint(
        name = "uk_planning_command_scope_key", columnNames = {"owner_account_id", "tenant_id", "operation", "key_hash"}))
public class PlanningCommandIdempotency {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(nullable = false, length = 48, updatable = false)
    private String operation;

    @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
    private String keyHash;

    @Column(nullable = false, length = 64, updatable = false)
    private String fingerprint;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanningCommandIdempotencyState state;

    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PlanningCommandIdempotency() {
    }

    public PlanningCommandIdempotency(
            UUID ownerAccountId, String tenantId, String operation, String keyHash, String fingerprint, UUID resourceId) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = ownerAccountId;
        this.tenantId = tenantId;
        this.operation = operation;
        this.keyHash = keyHash;
        this.fingerprint = fingerprint;
        this.resourceId = resourceId;
        this.state = PlanningCommandIdempotencyState.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getResourceId() { return resourceId; }
    public String getFingerprint() { return fingerprint; }
    public boolean isCompleted() { return state == PlanningCommandIdempotencyState.COMPLETED; }
    public String getResponseSnapshot() { return responseSnapshot; }

    public void complete(String snapshot) {
        responseSnapshot = snapshot;
        completedAt = Instant.now();
        state = PlanningCommandIdempotencyState.COMPLETED;
    }
}
