package com.lifeos.trustledger.proof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Privacy-minimized durable projection of a Document Vault proof command.
 *
 * <p>The request ID is also the CloudEvent id and therefore the idempotency key. The row contains
 * no filename, title, object-store reference, or document bytes. It remains pending until a
 * reviewed Besu/Web3j adapter is configured; this service never reports an unsubmitted anchor as
 * confirmed.
 */
@Entity
@Table(
        name = "trust_document_proof_request",
        indexes = @Index(name = "idx_trust_document_proof_owner", columnList = "owner_account_id,tenant_id"))
public class TrustDocumentProofRequest {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TrustDocumentProofState state;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "anchor_idempotency_key_hash", length = 64)
    private String anchorIdempotencyKeyHash;

    @Column(name = "transaction_hash", length = 66)
    private String transactionHash;

    @Column(name = "block_number")
    private Long blockNumber;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrustDocumentProofRequest() {
        // required by JPA
    }

    public TrustDocumentProofRequest(
            UUID requestId,
            UUID documentId,
            UUID ownerAccountId,
            String tenantId,
            long documentVersion,
            String checksumSha256,
            Instant receivedAt) {
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("tenantId must be bounded and nonblank");
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
        state = TrustDocumentProofState.PENDING_EXTERNAL_ANCHOR;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        updatedAt = receivedAt;
    }

    public UUID getRequestId() {
        return requestId;
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

    public TrustDocumentProofState getState() {
        return state;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getAnchorIdempotencyKeyHash() {
        return anchorIdempotencyKeyHash;
    }

    public String getTransactionHash() {
        return transactionHash;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void claim(String idempotencyKeyHash, Instant now) {
        if (anchorIdempotencyKeyHash == null) {
            anchorIdempotencyKeyHash = idempotencyKeyHash;
        } else if (!anchorIdempotencyKeyHash.equals(idempotencyKeyHash)) {
            throw new IllegalArgumentException("anchor idempotency key conflicts with the request");
        }
        state = TrustDocumentProofState.SUBMITTING;
        lastFailureCode = null;
        updatedAt = now;
    }

    public void markSubmitted(String transactionHash, Instant now) {
        this.transactionHash = Objects.requireNonNull(transactionHash, "transactionHash must not be null");
        state = TrustDocumentProofState.SUBMITTED;
        updatedAt = now;
    }

    public void markConfirmed(String transactionHash, long blockNumber, Instant now) {
        this.transactionHash = Objects.requireNonNull(transactionHash, "transactionHash must not be null");
        if (blockNumber < 0) {
            throw new IllegalArgumentException("blockNumber must not be negative");
        }
        this.blockNumber = blockNumber;
        state = TrustDocumentProofState.CONFIRMED;
        updatedAt = now;
    }

    public void markFailed(String failureCode, Instant now) {
        if (failureCode == null || !failureCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("failureCode must be a bounded classification");
        }
        lastFailureCode = failureCode;
        state = TrustDocumentProofState.FAILED;
        updatedAt = now;
    }

    public void resetPending(String failureCode, Instant now) {
        if (failureCode == null || !failureCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("failureCode must be a bounded classification");
        }
        lastFailureCode = failureCode;
        state = TrustDocumentProofState.PENDING_EXTERNAL_ANCHOR;
        updatedAt = now;
    }
}
