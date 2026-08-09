package com.lifeos.identity.auth;

import com.lifeos.identity.observability.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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

    /**
     * Creates the audit service.
     *
     * @param repository audit-event repository
     */
    public SecurityAuditService(SecurityAuditEventRepository repository) {
        this.repository = repository;
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
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        repository.save(new SecurityAuditEvent(
                eventType,
                accountId,
                correlationId,
                digest(clientAddress == null ? "unknown" : clientAddress),
                Instant.now()));
        log.atInfo()
                .addKeyValue("event", eventType.name().toLowerCase())
                .addKeyValue("correlationId", correlationId)
                .log("Authentication security outcome recorded");
    }

    /**
     * Derives a stable non-reversible client fingerprint.
     *
     * @param value raw client address held only during the request
     * @return SHA-256 client fingerprint
     */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
