package com.lifeos.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Scope-bound reservation for an idempotent endpoint enrollment request. */
@Entity
@Table(name = "notification_endpoint_registration_idempotency")
public class NotificationEndpointRegistrationIdempotency {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private EndpointRegistrationIdempotencyState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected NotificationEndpointRegistrationIdempotency() {
    }

    private NotificationEndpointRegistrationIdempotency(
            UUID ownerAccountId,
            String idempotencyKeyHash,
            String requestFingerprint,
            UUID endpointId,
            Instant now) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        this.state = EndpointRegistrationIdempotencyState.PENDING;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static NotificationEndpointRegistrationIdempotency pending(
            UUID ownerAccountId,
            String idempotencyKeyHash,
            String requestFingerprint,
            UUID endpointId,
            Instant now) {
        return new NotificationEndpointRegistrationIdempotency(
                ownerAccountId, idempotencyKeyHash, requestFingerprint, endpointId, now);
    }

    public void complete(Instant now) {
        if (state != EndpointRegistrationIdempotencyState.PENDING) {
            throw new IllegalStateException("endpoint registration reservation is already completed");
        }
        state = EndpointRegistrationIdempotencyState.COMPLETED;
        completedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public EndpointRegistrationIdempotencyState getState() {
        return state;
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hex digest");
        }
        return value;
    }
}
