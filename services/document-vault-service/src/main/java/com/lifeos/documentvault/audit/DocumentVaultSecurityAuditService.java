package com.lifeos.documentvault.audit;

import com.lifeos.documentvault.config.DocumentVaultServiceProperties;
import com.lifeos.documentvault.observability.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes redacted audit outcomes independently; only a keyed client-address digest is retained. */
@Service
public class DocumentVaultSecurityAuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final DocumentVaultSecurityAuditEventRepository repository;
    private final SecretKeySpec fingerprintKey;
    private final Clock clock;

    public DocumentVaultSecurityAuditService(
            DocumentVaultSecurityAuditEventRepository repository,
            DocumentVaultServiceProperties properties,
            Clock documentVaultClock) {
        this.repository = repository;
        fingerprintKey = new SecretKeySpec(
                properties.getAuditClientFingerprintSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        clock = documentVaultClock;
    }

    /** Audit failure is fail-closed for the response, while a client can retry a durable command. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DocumentVaultAuditEventType eventType, UUID accountId, String outcomeCode) {
        try {
            String correlationId = RequestContext.CORRELATION_ID.isBound()
                    ? RequestContext.CORRELATION_ID.get()
                    : "unbound";
            String clientAddress = RequestContext.CLIENT_ADDRESS.isBound()
                    ? RequestContext.CLIENT_ADDRESS.get()
                    : "unknown";
            repository.saveAndFlush(new DocumentVaultSecurityAuditEvent(
                    eventType, accountId, correlationId, digest(clientAddress), outcomeCode, clock.instant()));
        } catch (DocumentVaultAuditUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentVaultAuditUnavailableException();
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new DocumentVaultAuditUnavailableException();
        }
    }
}
