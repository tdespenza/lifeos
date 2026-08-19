package com.lifeos.profile.idempotency;

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

/**
 * Durable caller-scoped write reservation and immutable JSON response snapshot. Raw idempotency
 * keys and request bodies are deliberately absent; the digest fields are HMAC-SHA-256 values.
 */
@Entity
@Table(
        name = "profile_mutation_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_profile_mutation_idempotency_scope_key",
                columnNames = {"actor_account_id", "tenant_id", "operation", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_profile_mutation_idempotency_resource", columnList = "resource_id"))
public class ProfileMutationIdempotency {

    @Id
    private UUID id;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 64)
    private ProfileMutationOperation operation;

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
    private ProfileMutationIdempotencyState state;

    /**
     * Exact public representation returned by the original accepted request. It can contain the
     * user's profile fields, so it remains in the protected service database and is never logged.
     */
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

    protected ProfileMutationIdempotency() {
        // required by JPA
    }

    ProfileMutationIdempotency(
            UUID actorAccountId,
            String tenantId,
            ProfileMutationOperation operation,
            UUID resourceId,
            String idempotencyKeyHash,
            String requestFingerprint,
            long expectedVersion) {
        id = UUID.randomUUID();
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId must not be null");
        this.tenantId = requireBounded(tenantId, 255, "tenantId");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        if (expectedVersion < -1) {
            throw new IllegalArgumentException("expectedVersion must be at least -1");
        }
        this.expectedVersion = expectedVersion;
        state = ProfileMutationIdempotencyState.PENDING;
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
        return state == ProfileMutationIdempotencyState.COMPLETED;
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
            state = ProfileMutationIdempotencyState.COMPLETED;
        }
    }

    String completedSnapshot() {
        if (!isCompleted()
                || responseSnapshot == null
                || responseSnapshot.isBlank()
                || responseStatus == null
                || !hasConsistentResponseMetadata()) {
            throw new ProfileIdempotencyUnavailableException();
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

    private static int responseStatusFor(ProfileMutationOperation operation) {
        return operation == ProfileMutationOperation.CREATE_PROFILE || operation == ProfileMutationOperation.CREATE_HOUSEHOLD
                ? 201
                : 200;
    }

    private static String responseLocationFor(ProfileMutationOperation operation, UUID resourceId) {
        return switch (operation) {
            case CREATE_PROFILE -> "/api/v1/profiles/me";
            case CREATE_HOUSEHOLD -> "/api/v1/households/" + resourceId;
            default -> null;
        };
    }

    private static String requireBounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be a bounded non-blank value");
        }
        return value;
    }

    private static String requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
