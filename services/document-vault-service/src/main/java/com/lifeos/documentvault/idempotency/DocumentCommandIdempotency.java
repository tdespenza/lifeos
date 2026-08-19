package com.lifeos.documentvault.idempotency;

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
 * Durable owner-scoped command reservation. It stores only HMAC/digest values and an immutable
 * protected response snapshot; raw idempotency keys, bearer values, and document bytes are absent.
 */
@Entity
@Table(
        name = "document_command_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_command_idempotency_scope_key",
                columnNames = {"actor_account_id", "tenant_id", "operation", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_document_command_idempotency_document", columnList = "document_id"))
public class DocumentCommandIdempotency {

    @Id
    private UUID id;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private DocumentCommandOperation operation;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "expected_version", nullable = false, updatable = false)
    private long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentCommandIdempotencyState state;

    /** The snapshot is protected DB metadata and never appears in logs or metrics labels. */
    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentCommandIdempotency() {
        // required by JPA
    }

    DocumentCommandIdempotency(
            UUID actorAccountId,
            String tenantId,
            DocumentCommandOperation operation,
            UUID documentId,
            String idempotencyKeyHash,
            String requestFingerprint,
            long expectedVersion,
            Instant now) {
        id = UUID.randomUUID();
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId must not be null");
        this.tenantId = requireBounded(tenantId, "tenantId");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        if (expectedVersion < -1) {
            throw new IllegalArgumentException("expectedVersion must be at least -1");
        }
        this.expectedVersion = expectedVersion;
        state = DocumentCommandIdempotencyState.PENDING;
        createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    UUID getId() {
        return id;
    }

    UUID getDocumentId() {
        return documentId;
    }

    boolean matchesRequest(String candidateFingerprint) {
        return requestFingerprint.equals(candidateFingerprint);
    }

    boolean isCompleted() {
        return state == DocumentCommandIdempotencyState.COMPLETED;
    }

    void complete(String snapshot, Instant now) {
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException("response snapshot must not be blank");
        }
        if (!isCompleted()) {
            responseSnapshot = snapshot;
            completedAt = Objects.requireNonNull(now, "now must not be null");
            state = DocumentCommandIdempotencyState.COMPLETED;
        }
    }

    String completedSnapshot() {
        if (!isCompleted() || responseSnapshot == null || responseSnapshot.isBlank() || completedAt == null) {
            throw new DocumentIdempotencyUnavailableException();
        }
        return responseSnapshot;
    }

    private static String requireBounded(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 255) {
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
