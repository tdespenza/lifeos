package com.lifeos.documentvault.proof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Transactional outbox envelope; publishing is at-least-once and lease bounded. */
@Entity
@Table(
        name = "document_proof_outbox_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_document_proof_outbox_request", columnNames = "proof_request_id"),
        indexes = {
            @Index(name = "idx_document_proof_outbox_created", columnList = "created_at"),
            @Index(name = "idx_document_proof_outbox_claimable", columnList = "next_attempt_at, created_at")
        })
public class DocumentProofOutboxEvent {

    public static final String EVENT_TYPE = com.lifeos.events.v1.EventContract.DOCUMENT_PROOF_REQUESTED_V1_TYPE;

    @Id
    private UUID id;

    @Column(name = "proof_request_id", nullable = false, updatable = false)
    private UUID proofRequestId;

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

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentProofOutboxEvent() {
        // required by JPA
    }

    DocumentProofOutboxEvent(DocumentProofRequest request, String payloadJson, Instant createdAt) {
        id = UUID.randomUUID();
        proofRequestId = Objects.requireNonNull(request.getId(), "request id must not be null");
        documentId = request.getDocumentId();
        ownerAccountId = request.getOwnerAccountId();
        tenantId = request.getTenantId();
        documentVersion = request.getDocumentVersion();
        checksumSha256 = request.getChecksumSha256();
        eventType = EVENT_TYPE;
        if (payloadJson == null || payloadJson.isBlank() || payloadJson.length() > 1_000_000) {
            throw new IllegalArgumentException("payloadJson must be nonblank and bounded");
        }
        this.payloadJson = payloadJson;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        nextAttemptAt = this.createdAt;
    }

    UUID claim(Instant now, java.time.Duration leaseDuration) {
        if (publishedAt != null || deadLetteredAt != null || nextAttemptAt.isAfter(now)
                || (leaseUntil != null && !leaseUntil.isBefore(now))) {
            throw new IllegalStateException("proof outbox event is not claimable");
        }
        attemptCount++;
        leaseToken = UUID.randomUUID();
        leaseUntil = now.plus(Objects.requireNonNull(leaseDuration, "leaseDuration must not be null"));
        return leaseToken;
    }

    void markPublished(UUID expectedLease, Instant now) {
        requireLease(expectedLease);
        publishedAt = Objects.requireNonNull(now, "now must not be null");
        leaseToken = null;
        leaseUntil = null;
        lastFailureCode = null;
    }

    void reschedule(UUID expectedLease, Instant nextAttemptAt, String failureCode) {
        requireLease(expectedLease);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        lastFailureCode = requireFailureCode(failureCode);
        leaseToken = null;
        leaseUntil = null;
    }

    void deadLetter(UUID expectedLease, String failureCode, Instant now) {
        requireLease(expectedLease);
        deadLetteredAt = Objects.requireNonNull(now, "now must not be null");
        lastFailureCode = requireFailureCode(failureCode);
        leaseToken = null;
        leaseUntil = null;
    }

    private void requireLease(UUID expectedLease) {
        if (leaseToken == null || !leaseToken.equals(expectedLease) || leaseUntil == null) {
            throw new IllegalStateException("proof outbox lease is no longer held");
        }
    }

    private static String requireFailureCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("failure code must be bounded and safe");
        }
        return value;
    }

    public UUID getProofRequestId() {
        return proofRequestId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }
}
