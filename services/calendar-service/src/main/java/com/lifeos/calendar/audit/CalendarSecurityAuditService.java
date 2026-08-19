package com.lifeos.calendar.audit;

import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarAuditOutcome;
import com.lifeos.calendar.domain.CalendarSecurityAuditEvent;
import com.lifeos.calendar.domain.CalendarSecurityAuditEventRepository;
import com.lifeos.calendar.observability.RequestContext;
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

/** Writes redacted durable security facts without persisting a calendar's sensitive contents. */
@Service
public class CalendarSecurityAuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final CalendarSecurityAuditEventRepository repository;
    private final Clock clock;
    private final byte[] fingerprintSecret;

    public CalendarSecurityAuditService(
            CalendarSecurityAuditEventRepository repository, CalendarProperties properties, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        fingerprintSecret = properties.getAuditClientFingerprintSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** Persists only identifiers and safe codes; raw bearer, idempotency key, title, and location never enter it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            CalendarSubject subject,
            String action,
            CalendarAuditOutcome outcome,
            String targetType,
            UUID targetId,
            String reasonCode) {
        try {
            String correlationId = RequestContext.CORRELATION_ID.isBound()
                    ? RequestContext.CORRELATION_ID.get()
                    : "scheduler";
            String clientAddress = RequestContext.CLIENT_ADDRESS.isBound()
                    ? RequestContext.CLIENT_ADDRESS.get()
                    : "scheduler";
            repository.saveAndFlush(CalendarSecurityAuditEvent.redacted(
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
        } catch (CalendarAuditUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CalendarAuditUnavailableException(exception);
        }
    }

    private String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(fingerprintSecret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new CalendarAuditUnavailableException(exception);
        }
    }
}
