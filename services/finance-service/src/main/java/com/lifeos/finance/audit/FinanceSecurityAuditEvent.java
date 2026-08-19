package com.lifeos.finance.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable security decision record containing no bearer, client address, request body, or money data. */
@Entity
@Table(name = "finance_security_audit_event")
public class FinanceSecurityAuditEvent {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private FinanceSecurityAuditEventType eventType;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 128)
    private String correlationId;

    @Column(name = "client_fingerprint", nullable = false, updatable = false, length = 64)
    private String clientFingerprint;

    @Column(name = "outcome_code", nullable = false, updatable = false, length = 64)
    private String outcomeCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected FinanceSecurityAuditEvent() {
        // required by JPA
    }

    FinanceSecurityAuditEvent(
            FinanceSecurityAuditEventType eventType,
            UUID accountId,
            String correlationId,
            String clientFingerprint,
            String outcomeCode) {
        id = UUID.randomUUID();
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.accountId = accountId;
        this.correlationId = bounded(correlationId, 128, "correlationId");
        this.clientFingerprint = digest(clientFingerprint, "clientFingerprint");
        this.outcomeCode = bounded(outcomeCode, 64, "outcomeCode");
        occurredAt = Instant.now();
    }

    private static String bounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
