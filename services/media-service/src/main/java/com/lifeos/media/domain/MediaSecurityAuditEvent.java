package com.lifeos.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Redacted durable fact; media titles, bytes, object paths, credentials, and tokens are excluded. */
@Entity
@Table(name = "media_security_audit_event")
public class MediaSecurityAuditEvent {

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
    private MediaAuditOutcome outcome;

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

    protected MediaSecurityAuditEvent() {
    }

    private MediaSecurityAuditEvent(
            Instant occurredAt,
            UUID actorAccountId,
            UUID sessionId,
            String action,
            MediaAuditOutcome outcome,
            String targetType,
            UUID targetId,
            String correlationId,
            String clientFingerprint,
            String reasonCode) {
        id = UUID.randomUUID();
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.actorAccountId = actorAccountId;
        this.sessionId = sessionId;
        this.action = text(action, "action", 80);
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.targetType = text(targetType, "targetType", 64);
        this.targetId = targetId;
        this.correlationId = text(correlationId, "correlationId", 64);
        this.clientFingerprint = optionalDigest(clientFingerprint);
        this.reasonCode = optionalCode(reasonCode);
    }

    public static MediaSecurityAuditEvent redacted(
            Instant occurredAt,
            UUID actorAccountId,
            UUID sessionId,
            String action,
            MediaAuditOutcome outcome,
            String targetType,
            UUID targetId,
            String correlationId,
            String clientFingerprint,
            String reasonCode) {
        return new MediaSecurityAuditEvent(
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

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and bounded");
        }
        return value;
    }

    private static String optionalDigest(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("clientFingerprint must be a SHA-256 hex digest");
        }
        return value;
    }

    private static String optionalCode(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("reasonCode must be a bounded safe code");
        }
        return value;
    }
}
