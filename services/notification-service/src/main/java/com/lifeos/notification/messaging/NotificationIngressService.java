package com.lifeos.notification.messaging;

import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.events.v1.NotificationRequestedV2;
import com.lifeos.notification.persistence.NotificationInboxEvent;
import com.lifeos.notification.persistence.NotificationInboxEventRepository;
import com.lifeos.notification.security.SensitiveValueDigest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Durable inbox facade. It treats a committed same-payload CloudEvent ID as a successful duplicate
 * but rejects reuse of an ID with altered content, which would otherwise corrupt idempotency.
 */
@Service
public class NotificationIngressService {

    private final NotificationIngressTransactions transactions;
    private final NotificationInboxEventRepository inboxRepository;

    public NotificationIngressService(
            NotificationIngressTransactions transactions, NotificationInboxEventRepository inboxRepository) {
        this.transactions = transactions;
        this.inboxRepository = inboxRepository;
    }

    public NotificationIngressResult accept(CloudEventV1<NotificationRequestedV1> event, String payloadJson) {
        try {
            return transactions.acceptOnce(event, payloadJson);
        } catch (DataIntegrityViolationException exception) {
            return duplicateResult(event.id(), payloadJson, exception);
        }
    }

    /** Accepts a V2 request without weakening V1 validation or inbox collision handling. */
    public NotificationIngressResult acceptV2(CloudEventV1<NotificationRequestedV2> event, String payloadJson) {
        try {
            return transactions.acceptOnceV2(event, payloadJson);
        } catch (DataIntegrityViolationException exception) {
            return duplicateResult(event.id(), payloadJson, exception);
        }
    }

    private NotificationIngressResult duplicateResult(
            java.util.UUID eventId, String payloadJson, DataIntegrityViolationException exception) {
        NotificationInboxEvent existing = inboxRepository.findById(eventId).orElseThrow(() -> exception);
        if (!SensitiveValueDigest.sha256(payloadJson).equals(existing.getPayloadHash())) {
            throw new NotificationEventIdConflictException();
        }
        return new NotificationIngressResult(existing.getNotificationId(), true);
    }
}
