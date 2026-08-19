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

/** Durable idempotency reservation and immutable response snapshot for Calendar mutations. */
@Entity
@Table(name = "calendar_mutation_idempotency")
public class CalendarMutationIdempotency {

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
    private CalendarMutationIdempotencyState state;

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

    protected CalendarMutationIdempotency() {
    }

    private CalendarMutationIdempotency(
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
        this.tenantId = CalendarEvent.requireText(tenantId, "tenantId", 255);
        this.operation = CalendarEvent.requireText(operation, "operation", 64);
        this.resourceScope = CalendarEvent.requireText(resourceScope, "resourceScope", 128);
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestHash = requireDigest(requestHash, "requestHash");
        this.expectedVersion = expectedVersion;
        state = CalendarMutationIdempotencyState.PENDING;
        createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static CalendarMutationIdempotency pending(
            UUID actorAccountId,
            String tenantId,
            String operation,
            String resourceScope,
            String idempotencyKeyHash,
            String requestHash,
            Long expectedVersion,
            Instant now) {
        return new CalendarMutationIdempotency(
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
        if (state != CalendarMutationIdempotencyState.PENDING) {
            throw new IllegalStateException("idempotency reservation is already completed");
        }
        if (status < 200 || status > 299) {
            throw new IllegalArgumentException("only successful response snapshots are retained");
        }
        responseStatus = status;
        responseLocation = location == null ? null : CalendarEvent.requireText(location, "location", 255);
        responseJson = CalendarEvent.requireText(json, "json", 32_000);
        completedAt = Objects.requireNonNull(now, "now must not be null");
        state = CalendarMutationIdempotencyState.COMPLETED;
    }

    public boolean matches(String candidateRequestHash, Long candidateExpectedVersion) {
        return requestHash.equals(candidateRequestHash) && Objects.equals(expectedVersion, candidateExpectedVersion);
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return value;
    }

    public CalendarMutationIdempotencyState getState() {
        return state;
    }

    public UUID getId() {
        return id;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseLocation() {
        return responseLocation;
    }
}
