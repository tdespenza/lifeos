package com.lifeos.notification.audit;

import com.lifeos.notification.observability.RequestContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes one redacted audit fact in an independent transaction. */
@Service
public class NotificationSecurityAuditService {

    private final NotificationSecurityAuditEventRepository repository;
    private final Clock clock;

    public NotificationSecurityAuditService(NotificationSecurityAuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID actorAccountId,
            UUID sessionId,
            String eventType,
            NotificationSecurityAuditOutcome outcome,
            UUID targetId,
            String reasonCode) {
        repository.save(NotificationSecurityAuditEvent.create(
                actorAccountId,
                sessionId,
                eventType,
                outcome,
                targetId,
                correlationId(),
                reasonCode,
                clock.instant()));
    }

    private static UUID correlationId() {
        if (!RequestContext.CORRELATION_ID.isBound()) {
            return null;
        }
        try {
            return UUID.fromString(RequestContext.CORRELATION_ID.get());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
