package com.lifeos.documentvault.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, redacted audit fact. It excludes document IDs, titles, tags, object references, raw
 * addresses, bearer values, content checksums, and idempotency keys.
 */
@Entity
@Table(name = "document_vault_security_audit_event")
public class DocumentVaultSecurityAuditEvent {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private DocumentVaultAuditEventType eventType;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "client_fingerprint", nullable = false, length = 64)
    private String clientFingerprint;

    @Column(name = "outcome_code", nullable = false, length = 64)
    private String outcomeCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected DocumentVaultSecurityAuditEvent() {
        // required by JPA
    }

    DocumentVaultSecurityAuditEvent(
            DocumentVaultAuditEventType eventType,
            UUID accountId,
            String correlationId,
            String clientFingerprint,
            String outcomeCode,
            Instant occurredAt) {
        id = UUID.randomUUID();
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.accountId = accountId;
        this.correlationId = requireBounded(correlationId, "correlationId");
        this.clientFingerprint = requireDigest(clientFingerprint);
        this.outcomeCode = requireOutcomeCode(outcomeCode);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String requireBounded(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must be a bounded non-blank value");
        }
        return value;
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("clientFingerprint must be a SHA-256 digest");
        }
        return value;
    }

    private static String requireOutcomeCode(String value) {
        if (value == null || !value.matches("[A-Z_]{1,64}")) {
            throw new IllegalArgumentException("outcomeCode must be a bounded enum-like value");
        }
        return value;
    }
}
