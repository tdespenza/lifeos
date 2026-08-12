package com.lifeos.identity.auth;

import com.lifeos.identity.observability.RequestContext;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes redacted authentication audit events and structured operational outcomes.
 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    private final SecurityAuditEventRepository repository;
    private final HmacSha256Digest clientFingerprint;

    /**
     * Creates the audit service.
     *
     * @param repository audit-event repository
     * @param properties authentication properties containing the dedicated audit fingerprint key
     */
    public SecurityAuditService(SecurityAuditEventRepository repository, IdentityAuthProperties properties) {
        this.repository = repository;
        this.clientFingerprint = new HmacSha256Digest(
                properties.getFingerprint().getAuditClientFingerprintSecret(),
                "IDENTITY_AUDIT_CLIENT_FINGERPRINT_SECRET");
    }

    /**
     * Persists one redacted authentication outcome in an independent transaction so rejected
     * login attempts remain auditable even when the calling authentication transaction rolls back.
     *
     * @param eventType security outcome
     * @param accountId known account, or {@code null}
     * @param clientAddress request source used only to derive a digest
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SecurityAuditEventType eventType, UUID accountId, String clientAddress) {
        persist(eventType, accountId, clientAddress);
    }

    /**
     * Persists an outcome in the caller's transaction. This is used for successful authentication
     * so the audit row commits or rolls back with the durable session.
     *
     * @param eventType security outcome
     * @param accountId known account, or {@code null}
     * @param clientAddress request source used only to derive a digest
     */
    @Transactional
    public void recordWithinCurrentTransaction(
            SecurityAuditEventType eventType, UUID accountId, String clientAddress) {
        persist(eventType, accountId, clientAddress);
    }

    private void persist(SecurityAuditEventType eventType, UUID accountId, String clientAddress) {
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        repository.saveAndFlush(new SecurityAuditEvent(
                eventType,
                accountId,
                correlationId,
                clientFingerprint.digest(clientAddress == null ? "unknown" : clientAddress),
                Instant.now()));
        log.atInfo()
                .addKeyValue("event", eventType.name().toLowerCase())
                .log("Authentication security outcome recorded");
    }

}
