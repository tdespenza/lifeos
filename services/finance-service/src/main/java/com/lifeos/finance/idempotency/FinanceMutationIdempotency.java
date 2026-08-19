package com.lifeos.finance.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable caller-scoped mutation reservation and immutable public response snapshot. */
@Entity
@Table(
        name = "finance_mutation_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_finance_mutation_idempotency_scope_key",
                columnNames = {"actor_account_id", "tenant_id", "operation", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_finance_mutation_idempotency_resource", columnList = "resource_id"))
public class FinanceMutationIdempotency {

    @Id
    private UUID id;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 64)
    private FinanceMutationOperation operation;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "expected_version", nullable = false, updatable = false)
    private long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FinanceMutationIdempotencyState state;

    /** Exact original representation. It remains in the protected finance database and is never logged. */
    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_location", length = 256)
    private String responseLocation;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinanceMutationIdempotency() {
        // required by JPA
    }

    FinanceMutationIdempotency(
            UUID actorAccountId,
            String tenantId,
            FinanceMutationOperation operation,
            UUID resourceId,
            String idempotencyKeyHash,
            String requestFingerprint,
            long expectedVersion) {
        id = UUID.randomUUID();
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId must not be null");
        this.tenantId = bounded(tenantId, 255, "tenantId");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
        this.idempotencyKeyHash = digest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = digest(requestFingerprint, "requestFingerprint");
        if (expectedVersion < -1) {
            throw new IllegalArgumentException("expectedVersion must be at least -1");
        }
        this.expectedVersion = expectedVersion;
        state = FinanceMutationIdempotencyState.PENDING;
        createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getResourceId() {
        return resourceId;
    }

    boolean matchesRequest(String candidateFingerprint) {
        return requestFingerprint.equals(candidateFingerprint);
    }

    boolean isCompleted() {
        return state == FinanceMutationIdempotencyState.COMPLETED;
    }

    void complete(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException("response snapshot must not be blank");
        }
        if (!isCompleted()) {
            responseSnapshot = snapshot;
            responseStatus = responseStatusFor(operation);
            responseLocation = responseLocationFor(operation, resourceId);
            completedAt = Instant.now();
            state = FinanceMutationIdempotencyState.COMPLETED;
        }
    }

    String completedSnapshot() {
        if (!isCompleted()
                || responseSnapshot == null
                || responseSnapshot.isBlank()
                || responseStatus == null
                || !hasConsistentResponseMetadata()) {
            throw new FinanceIdempotencyUnavailableException();
        }
        return responseSnapshot;
    }

    int completedResponseStatus() {
        completedSnapshot();
        return responseStatus;
    }

    String completedResponseLocation() {
        completedSnapshot();
        return responseLocation;
    }

    private boolean hasConsistentResponseMetadata() {
        return (responseStatus == 200 && responseLocation == null)
                || (responseStatus == 201 && responseLocation != null && !responseLocation.isBlank());
    }

    private static int responseStatusFor(FinanceMutationOperation operation) {
        return switch (operation) {
            case CREATE_BUDGET, CREATE_TRANSACTION, CREATE_GOAL -> 201;
            case UPDATE_BUDGET, CATEGORIZE_TRANSACTION, UPDATE_GOAL, CONTRIBUTE_GOAL -> 200;
        };
    }

    private static String responseLocationFor(FinanceMutationOperation operation, UUID resourceId) {
        return switch (operation) {
            case CREATE_BUDGET -> "/api/v1/finance/budgets/" + resourceId;
            case CREATE_TRANSACTION -> "/api/v1/finance/transactions/" + resourceId;
            case CREATE_GOAL -> "/api/v1/finance/goals/" + resourceId;
            case UPDATE_BUDGET, CATEGORIZE_TRANSACTION, UPDATE_GOAL, CONTRIBUTE_GOAL -> null;
        };
    }

    private static String bounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
