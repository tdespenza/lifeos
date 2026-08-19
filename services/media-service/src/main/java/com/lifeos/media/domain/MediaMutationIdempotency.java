package com.lifeos.media.domain;

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

/** Scoped durable reservation retaining only hashes and a successful public response snapshot. */
@Entity
@Table(name = "media_mutation_idempotency")
public class MediaMutationIdempotency {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(nullable = false, length = 64, updatable = false)
    private String operation;

    @Column(name = "resource_scope", nullable = false, length = 128, updatable = false)
    private String resourceScope;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "expected_version", updatable = false)
    private Long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MediaMutationIdempotencyState state;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_location", length = 255)
    private String responseLocation;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MediaMutationIdempotency() {
    }

    private MediaMutationIdempotency(
            UUID actorAccountId,
            String tenantId,
            String operation,
            String resourceScope,
            String idempotencyKeyHash,
            String requestHash,
            Long expectedVersion,
            Instant now) {
        id = UUID.randomUUID();
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId must not be null");
        this.tenantId = text(tenantId, "tenantId", 255);
        this.operation = text(operation, "operation", 64);
        this.resourceScope = text(resourceScope, "resourceScope", 128);
        this.idempotencyKeyHash = digest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestHash = digest(requestHash, "requestHash");
        this.expectedVersion = expectedVersion;
        state = MediaMutationIdempotencyState.PENDING;
        createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static MediaMutationIdempotency pending(
            UUID actorAccountId,
            String tenantId,
            String operation,
            String resourceScope,
            String idempotencyKeyHash,
            String requestHash,
            Long expectedVersion,
            Instant now) {
        return new MediaMutationIdempotency(
                actorAccountId,
                tenantId,
                operation,
                resourceScope,
                idempotencyKeyHash,
                requestHash,
                expectedVersion,
                now);
    }

    public void complete(int status, String location, String json, Instant now) {
        if (state != MediaMutationIdempotencyState.PENDING) {
            throw new IllegalStateException("idempotency reservation is already completed");
        }
        if (status < 200 || status > 299) {
            throw new IllegalArgumentException("only successful response snapshots are retained");
        }
        responseStatus = status;
        responseLocation = location == null ? null : text(location, "location", 255);
        responseJson = text(json, "json", 32_000);
        completedAt = Objects.requireNonNull(now, "now must not be null");
        state = MediaMutationIdempotencyState.COMPLETED;
    }

    public boolean matches(String candidateRequestHash, Long candidateExpectedVersion) {
        return requestHash.equals(candidateRequestHash) && Objects.equals(expectedVersion, candidateExpectedVersion);
    }

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and bounded");
        }
        return value;
    }

    private static String digest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public MediaMutationIdempotencyState getState() {
        return state;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseLocation() {
        return responseLocation;
    }

    public String getResponseJson() {
        return responseJson;
    }
}
