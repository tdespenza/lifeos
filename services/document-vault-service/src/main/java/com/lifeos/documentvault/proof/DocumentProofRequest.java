package com.lifeos.documentvault.proof;

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

/** Immutable owner-scoped request for a future Trust Ledger document proof. */
@Entity
@Table(
        name = "document_proof_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_proof_request_scope_key",
                columnNames = {"owner_account_id", "tenant_id", "idempotency_key_hash"}),
        indexes = @Index(name = "idx_document_proof_request_document", columnList = "document_id"))
public class DocumentProofRequest {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(name = "document_version", nullable = false, updatable = false)
    private long documentVersion;

    @Column(name = "checksum_sha256", nullable = false, updatable = false, length = 64)
    private String checksumSha256;

    @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private DocumentProofRequestState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentProofRequest() {
        // required by JPA
    }

    DocumentProofRequest(
            UUID documentId,
            UUID ownerAccountId,
            String tenantId,
            long documentVersion,
            String checksumSha256,
            String idempotencyKeyHash,
            String requestFingerprint,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("tenantId must be bounded and non-blank");
        }
        this.tenantId = tenantId;
        if (documentVersion < 0) {
            throw new IllegalArgumentException("documentVersion must not be negative");
        }
        this.documentVersion = documentVersion;
        if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a SHA-256 digest");
        }
        this.checksumSha256 = checksumSha256;
        this.idempotencyKeyHash = digest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = digest(requestFingerprint, "requestFingerprint");
        this.state = DocumentProofRequestState.REQUESTED;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public long getDocumentVersion() {
        return documentVersion;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public DocumentProofRequestState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    boolean matches(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    void markFailed() {
        if (state == DocumentProofRequestState.REQUESTED) {
            state = DocumentProofRequestState.FAILED;
        }
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
