package com.lifeos.notification.messaging;

import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.events.v1.NotificationRequestedV2;
import com.lifeos.notification.persistence.NotificationDelivery;
import com.lifeos.notification.persistence.NotificationDeliveryRepository;
import com.lifeos.notification.persistence.NotificationEndpoint;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import com.lifeos.notification.persistence.NotificationInboxEvent;
import com.lifeos.notification.persistence.NotificationInboxEventRepository;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.security.SensitiveValueDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate Spring proxy boundary for an atomic inbox reservation and notification write. */
@Service
public class NotificationIngressTransactions {

    private final NotificationInboxEventRepository inboxRepository;
    private final NotificationRecordRepository recordRepository;
    private final NotificationEndpointRepository endpointRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationSequenceAllocator sequenceAllocator;
    private final Clock clock;

    public NotificationIngressTransactions(
            NotificationInboxEventRepository inboxRepository,
            NotificationRecordRepository recordRepository,
            NotificationEndpointRepository endpointRepository,
            NotificationDeliveryRepository deliveryRepository,
            NotificationSequenceAllocator sequenceAllocator,
            Clock clock) {
        this.inboxRepository = inboxRepository;
        this.recordRepository = recordRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.clock = clock;
    }

    /**
     * Claims the CloudEvents ID with an atomic SQL INSERT before any domain write. A primary-key
     * conflict rolls back this whole transaction and is resolved outside it as a duplicate
     * acknowledgement. We must not call JpaRepository.save here: assigned IDs use merge semantics
     * and do not provide an insert-only inbox reservation.
     */
    @Transactional
    public NotificationIngressResult acceptOnce(CloudEventV1<NotificationRequestedV1> event, String payloadJson) {
        validateV1(event);
        return acceptOnce(
                event.id(),
                event.source().toString(),
                event.type(),
                event.correlationId(),
                event.data(),
                null,
                payloadJson);
    }

    /** Accepts V2 without reinterpreting V1 or losing the original source event type in the inbox. */
    @Transactional
    public NotificationIngressResult acceptOnceV2(CloudEventV1<NotificationRequestedV2> event, String payloadJson) {
        validateV2(event);
        return acceptOnce(
                event.id(),
                event.source().toString(),
                event.type(),
                event.correlationId(),
                event.data().asV1(),
                event.data().eventTimeZone(),
                payloadJson);
    }

    private NotificationIngressResult acceptOnce(
            java.util.UUID eventId,
            String source,
            String eventType,
            java.util.UUID correlationId,
            NotificationRequestedV1 request,
            String eventTimeZone,
            String payloadJson) {
        if (!request.recipientAccountId().toString().equals(request.tenantId())) {
            // LifeOS currently has a one-account tenant model. Do not let an untrusted or buggy
            // producer cross that boundary until multi-account tenant membership is introduced.
            throw new InvalidNotificationEventException("notification recipient and tenant scope do not match");
        }
        Instant now = clock.instant();
        String payloadHash = SensitiveValueDigest.sha256(payloadJson);
        inboxRepository.reserve(
                eventId, source, eventType, correlationId, payloadHash, now);
        long sequence = sequenceAllocator.next(request.recipientAccountId());
        NotificationRecord record =
                recordRepository.save(NotificationRecord.from(
                        eventId, correlationId, request, sequence, eventTimeZone, now));
        createDeliveries(record, request, now);
        inboxRepository.findById(eventId).orElseThrow().markProcessed(record.getId(), now);
        return new NotificationIngressResult(record.getId(), false);
    }

    private void createDeliveries(NotificationRecord record, NotificationRequestedV1 request, Instant now) {
        for (NotificationChannel channel : request.requestedChannels()) {
            if (channel == NotificationChannel.REALTIME) {
                deliveryRepository.save(NotificationDelivery.pending(
                        record.getId(),
                        record.getSourceEventId(),
                        record.getRecipientAccountId(),
                        channel,
                        null,
                        now));
                continue;
            }
            List<NotificationEndpoint> endpoints = endpointRepository.findByOwnerAccountIdAndChannelAndEnabledTrue(
                    record.getRecipientAccountId(), channel);
            if (endpoints.isEmpty()) {
                // Persist a work item rather than silently dropping the channel. The worker will
                // record a SKIPPED/NO_ENABLED_ENDPOINT outcome and publish it through the outbox.
                deliveryRepository.save(NotificationDelivery.pending(
                        record.getId(),
                        record.getSourceEventId(),
                        record.getRecipientAccountId(),
                        channel,
                        null,
                        now));
                continue;
            }
            endpoints.forEach(endpoint -> deliveryRepository.save(NotificationDelivery.pending(
                    record.getId(),
                    record.getSourceEventId(),
                    record.getRecipientAccountId(),
                    channel,
                    endpoint.getId(),
                    now)));
        }
    }

    private static void validateV1(CloudEventV1<NotificationRequestedV1> event) {
        if (!EventContract.NOTIFICATION_REQUESTED_V1_TYPE.equals(event.type())) {
            throw new InvalidNotificationEventException("unsupported notification event type");
        }
        String expectedSubject = "notification/" + event.data().notificationId();
        if (!expectedSubject.equals(event.subject())) {
            throw new InvalidNotificationEventException("notification event subject does not match notification ID");
        }
    }

    private static void validateV2(CloudEventV1<NotificationRequestedV2> event) {
        if (!EventContract.NOTIFICATION_REQUESTED_V2_TYPE.equals(event.type())) {
            throw new InvalidNotificationEventException("unsupported notification event type");
        }
        String expectedSubject = "notification/" + event.data().notificationId();
        if (!expectedSubject.equals(event.subject())) {
            throw new InvalidNotificationEventException("notification event subject does not match notification ID");
        }
    }
}
