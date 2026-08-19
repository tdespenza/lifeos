package com.lifeos.media.audit;

import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.config.MediaProperties;
import com.lifeos.media.domain.MediaAuditOutcome;
import com.lifeos.media.domain.MediaSecurityAuditEvent;
import com.lifeos.media.domain.MediaSecurityAuditEventRepository;
import com.lifeos.media.observability.RequestContext;
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

/** Persists durable, redacted security facts without retaining sensitive media or credentials. */
@Service
public class MediaSecurityAuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final MediaSecurityAuditEventRepository repository;
    private final Clock clock;
    private final byte[] fingerprintSecret;

    public MediaSecurityAuditService(
            MediaSecurityAuditEventRepository repository, MediaProperties properties, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        fingerprintSecret = properties.getAuditClientFingerprintSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** Raw bearer tokens, object paths, idempotency keys, media titles, and room permits never enter this record. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            MediaSubject subject,
            String action,
            MediaAuditOutcome outcome,
            String targetType,
            UUID targetId,
            String reasonCode) {
        try {
            String correlationId = RequestContext.CORRELATION_ID.isBound()
                    ? RequestContext.CORRELATION_ID.get()
                    : "media-service";
            String clientAddress = RequestContext.CLIENT_ADDRESS.isBound()
                    ? RequestContext.CLIENT_ADDRESS.get()
                    : "unknown";
            repository.saveAndFlush(MediaSecurityAuditEvent.redacted(
                    clock.instant(),
                    subject == null ? null : subject.accountId(),
                    subject == null ? null : subject.sessionId(),
                    action,
                    outcome,
                    targetType,
                    targetId,
                    correlationId,
                    fingerprint(clientAddress),
                    reasonCode));
        } catch (MediaAuditUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaAuditUnavailableException(exception);
        }
    }

    private String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(fingerprintSecret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new MediaAuditUnavailableException(exception);
        }
    }
}
