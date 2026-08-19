package com.lifeos.notification.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Privacy-minimized durable audit fact; sensitive endpoint/auth material is intentionally absent. */
@Entity
@Table(name = "notification_security_audit_event")
public class NotificationSecurityAuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_account_id", updatable = false)
    private UUID actorAccountId;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private NotificationSecurityAuditOutcome outcome;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "reason_code", nullable = false, length = 80, updatable = false)
    private String reasonCode;

    protected NotificationSecurityAuditEvent() {
    }

    private NotificationSecurityAuditEvent(
            UUID actorAccountId,
            UUID sessionId,
            String eventType,
            NotificationSecurityAuditOutcome outcome,
            UUID targetId,
            UUID correlationId,
            String reasonCode,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.actorAccountId = actorAccountId;
        this.sessionId = sessionId;
        this.eventType = requireCode(eventType, 64);
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.targetId = targetId;
        this.correlationId = correlationId;
        this.reasonCode = requireCode(reasonCode, 80);
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public static NotificationSecurityAuditEvent create(
            UUID actorAccountId,
            UUID sessionId,
            String eventType,
            NotificationSecurityAuditOutcome outcome,
            UUID targetId,
            UUID correlationId,
            String reasonCode,
            Instant occurredAt) {
        return new NotificationSecurityAuditEvent(
                actorAccountId, sessionId, eventType, outcome, targetId, correlationId, reasonCode, occurredAt);
    }

    public String getEventType() {
        return eventType;
    }

    public NotificationSecurityAuditOutcome getOutcome() {
        return outcome;
    }

    public UUID getTargetId() {
        return targetId;
    }

    private static String requireCode(String value, int maximumLength) {
        if (value == null || !value.matches("[A-Z0-9_]{1," + maximumLength + "}")) {
            throw new IllegalArgumentException("audit code must be bounded uppercase token");
        }
        return value;
    }
}
