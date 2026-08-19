package com.lifeos.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Redacted durable audit fact; it deliberately stores no calendar title, body, location, or token. */
@Entity
@Table(name = "calendar_security_audit_event")
public class CalendarSecurityAuditEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_account_id", updatable = false)
    private UUID actorAccountId;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(nullable = false, length = 80, updatable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private CalendarAuditOutcome outcome;

    @Column(name = "target_type", nullable = false, length = 64, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false)
    private String correlationId;

    @Column(name = "client_fingerprint", length = 64, updatable = false)
    private String clientFingerprint;

    @Column(name = "reason_code", length = 80, updatable = false)
    private String reasonCode;

    protected CalendarSecurityAuditEvent() {
    }

    private CalendarSecurityAuditEvent(
            Instant occurredAt,
            UUID actorAccountId,
            UUID sessionId,
            String action,
            CalendarAuditOutcome outcome,
            String targetType,
            UUID targetId,
            String correlationId,
            String clientFingerprint,
            String reasonCode) {
        id = UUID.randomUUID();
        this.occurredAt = occurredAt;
        this.actorAccountId = actorAccountId;
        this.sessionId = sessionId;
        this.action = CalendarEvent.requireText(action, "action", 80);
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.targetType = CalendarEvent.requireText(targetType, "targetType", 64);
        this.targetId = targetId;
        this.correlationId = CalendarEvent.requireText(correlationId, "correlationId", 64);
        this.clientFingerprint = requireOptionalFingerprint(clientFingerprint);
        this.reasonCode = requireOptionalCode(reasonCode);
    }

    public static CalendarSecurityAuditEvent redacted(
            Instant occurredAt,
            UUID actorAccountId,
            UUID sessionId,
            String action,
            CalendarAuditOutcome outcome,
            String targetType,
            UUID targetId,
            String correlationId,
            String clientFingerprint,
            String reasonCode) {
        return new CalendarSecurityAuditEvent(
                occurredAt,
                actorAccountId,
                sessionId,
                action,
                outcome,
                targetType,
                targetId,
                correlationId,
                clientFingerprint,
                reasonCode);
    }

    private static String requireOptionalFingerprint(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("clientFingerprint must be a SHA-256 hex digest");
        }
        return value;
    }

    private static String requireOptionalCode(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("reasonCode must be a bounded safe code");
        }
        return value;
    }
}
