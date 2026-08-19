package com.lifeos.profile.audit;

import com.lifeos.profile.config.ProfileServiceProperties;
import com.lifeos.profile.observability.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists security decisions in independent transactions with a keyed, redacted client digest. */
@Service
public class ProfileSecurityAuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ProfileSecurityAuditEventRepository repository;
    private final SecretKeySpec fingerprintKey;

    public ProfileSecurityAuditService(
            ProfileSecurityAuditEventRepository repository, ProfileServiceProperties properties) {
        this.repository = repository;
        fingerprintKey = new SecretKeySpec(
                properties.getAuditClientFingerprintSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * Writes a decision independently so denials remain auditable even if the caller rolls back.
     * Audit persistence is part of the security boundary: failure becomes a fail-closed 503.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ProfileSecurityAuditEventType eventType, UUID accountId, String outcomeCode) {
        try {
            String correlationId = RequestContext.CORRELATION_ID.isBound()
                    ? RequestContext.CORRELATION_ID.get()
                    : "unbound";
            String address = RequestContext.CLIENT_ADDRESS.isBound()
                    ? RequestContext.CLIENT_ADDRESS.get()
                    : "unknown";
            repository.saveAndFlush(new ProfileSecurityAuditEvent(
                    eventType, accountId, correlationId, digest(address), outcomeCode));
        } catch (ProfileAuditUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProfileAuditUnavailableException(exception);
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
            throw new ProfileAuditUnavailableException(exception);
        }
    }
}
