package com.lifeos.finance.audit;

import com.lifeos.finance.config.FinanceServiceProperties;
import com.lifeos.finance.observability.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists security decisions independently with a keyed, redacted client fingerprint. */
@Service
public class FinanceSecurityAuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final FinanceSecurityAuditEventRepository repository;
    private final SecretKeySpec fingerprintKey;

    public FinanceSecurityAuditService(
            FinanceSecurityAuditEventRepository repository, FinanceServiceProperties properties) {
        this.repository = repository;
        fingerprintKey = new SecretKeySpec(
                properties.getAuditClientFingerprintSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** Audit failures fail closed so a security-relevant allow/deny cannot become unaudited. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(FinanceSecurityAuditEventType eventType, UUID accountId, String outcomeCode) {
        try {
            String correlationId = RequestContext.CORRELATION_ID.isBound()
                    ? RequestContext.CORRELATION_ID.get()
                    : "unbound";
            String address = RequestContext.CLIENT_ADDRESS.isBound()
                    ? RequestContext.CLIENT_ADDRESS.get()
                    : "unknown";
            repository.saveAndFlush(new FinanceSecurityAuditEvent(
                    eventType, accountId, correlationId, digest(address), outcomeCode));
        } catch (FinanceAuditUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FinanceAuditUnavailableException(exception);
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(fingerprintKey);
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                encoded.append(String.format("%02x", valueByte));
            }
            return encoded.toString();
        } catch (GeneralSecurityException exception) {
            throw new FinanceAuditUnavailableException(exception);
        }
    }
}
