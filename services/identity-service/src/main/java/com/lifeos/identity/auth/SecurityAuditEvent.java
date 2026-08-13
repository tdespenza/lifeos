package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable, redacted security audit record for authentication outcomes.
 *
 * <p>Email addresses, passwords, password hashes, bearer tokens, cookies, and raw network
 * addresses are intentionally absent. The client fingerprint is a keyed one-way digest.
 */
@Entity
@Table(name = "security_audit_event")
public class SecurityAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    @Enumerated(EnumType.STRING)
    private SecurityAuditEventType eventType;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "client_fingerprint", nullable = false, length = 64)
    private String clientFingerprint;

    /**
     * Bounded, non-sensitive authorization classification. Authentication events leave this
     * unset; authorization events use a stable enum-like reason rather than a resource identifier
     * or request content.
     */
    @Column(name = "outcome_code", length = 64)
    private String outcomeCode;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    /**
     * Creates an empty entity for JPA materialization.
     */
    protected SecurityAuditEvent() {
        // required by JPA
    }

    /**
     * Creates a redacted security audit event.
     *
     * @param eventType outcome classification
     * @param accountId known account, or {@code null} when no account was identified
     * @param correlationId request correlation identifier
     * @param clientFingerprint one-way client fingerprint
     * @param occurredAt event timestamp
     */
    public SecurityAuditEvent(SecurityAuditEventType eventType, UUID accountId,
            String correlationId, String clientFingerprint, Instant occurredAt) {
        this(eventType, accountId, correlationId, clientFingerprint, null, occurredAt);
    }

    /**
     * Creates a redacted security audit event with an optional bounded outcome classification.
     *
     * @param eventType outcome classification
     * @param accountId known account, or {@code null} when no account was identified
     * @param correlationId request correlation identifier
     * @param clientFingerprint one-way client fingerprint
     * @param outcomeCode bounded reason code, or {@code null} for authentication events
     * @param occurredAt event timestamp
     */
    public SecurityAuditEvent(SecurityAuditEventType eventType, UUID accountId,
            String correlationId, String clientFingerprint, String outcomeCode, Instant occurredAt) {
        this.eventType = eventType;
        this.accountId = accountId;
        this.correlationId = correlationId;
        this.clientFingerprint = clientFingerprint;
        this.outcomeCode = outcomeCode;
        this.occurredAt = occurredAt;
    }

    /**
     * Returns the event classification.
     *
     * @return audit event type
     */
    public SecurityAuditEventType getEventType() {
        return eventType;
    }

    /**
     * Returns the associated account when the lookup found one.
     *
     * @return account UUID, or {@code null}
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Returns the request correlation identifier.
     *
     * @return correlation id
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * Returns the bounded non-sensitive authorization classification, if present.
     *
     * @return authorization reason code or {@code null} for authentication events
     */
    public String getOutcomeCode() {
        return outcomeCode;
    }
}
