package com.lifeos.assistant.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable, privacy-minimized outbox envelope for a future hash-only Trust Ledger producer. */
@Entity
@Table(
        name = "ai_audit_hash_outbox_event",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_audit_hash_outbox_audit_event", columnNames = "audit_event_id"),
        indexes = @Index(name = "idx_ai_audit_hash_outbox_created", columnList = "created_at"))
public class AiAuditHashOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "audit_event_id", nullable = false, updatable = false)
    private UUID auditEventId;

    @Column(name = "owner_account_id", updatable = false)
    private UUID ownerAccountId;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "audit_hash_sha256", nullable = false, updatable = false, length = 64)
    private String auditHashSha256;

    @Column(name = "event_type", nullable = false, updatable = false, length = 96)
    private String eventType;

    @Column(name = "topic", nullable = false, updatable = false, length = 128)
    private String topic;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    protected AiAuditHashOutboxEvent() {
        // required by JPA
    }

    AiAuditHashOutboxEvent(
            AssistantRequestAuditEvent auditEvent,
            String eventType,
            String topic,
            String payloadJson,
            Instant createdAt) {
        id = Objects.requireNonNull(auditEvent.getId(), "audit event id must not be null");
        auditEventId = auditEvent.getId();
        ownerAccountId = auditEvent.getOwnerAccountId();
        conversationId = auditEvent.getConversationId();
        auditHashSha256 = requireDigest(auditEvent.getAuditHashSha256());
        this.eventType = requireBounded(eventType, 96, "eventType");
        this.topic = requireBounded(topic, 128, "topic");
        this.payloadJson = requireBounded(payloadJson, 1_000_000, "payloadJson");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        nextAttemptAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuditEventId() {
        return auditEventId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getAuditHashSha256() {
        return auditHashSha256;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    int getAttemptCount() {
        return attemptCount;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    UUID getLeaseToken() {
        return leaseToken;
    }

    UUID claim(Instant now, java.time.Duration leaseDuration) {
        if (publishedAt != null || deadLetteredAt != null || nextAttemptAt.isAfter(now)
                || (leaseUntil != null && !leaseUntil.isBefore(now))) {
            throw new IllegalStateException("AI audit outbox event is not claimable");
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
            throw new IllegalStateException("AI audit outbox lease is no longer held");
        }
    }

    private static String requireFailureCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("failure code must be bounded and safe");
        }
        return value;
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("audit hash must be a SHA-256 digest");
        }
        return value;
    }

    private static String requireBounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }
}
