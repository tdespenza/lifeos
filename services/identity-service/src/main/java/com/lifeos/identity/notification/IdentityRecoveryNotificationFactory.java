package com.lifeos.identity.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV2;
import com.lifeos.identity.observability.RequestContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Builds generic recovery security notifications without serializing secrets or account PII. */
@Component
public class IdentityRecoveryNotificationFactory {

    private static final String SOURCE = "urn:lifeos:identity-service";
    private static final String EVENT_TIME_ZONE = "UTC";
    private static final Set<NotificationChannel> CHANNELS = Set.of(
            NotificationChannel.EMAIL,
            NotificationChannel.PUSH,
            NotificationChannel.REALTIME);

    private final ObjectMapper objectMapper;
    private final IdentityRecoveryNotificationProperties properties;
    private final Clock clock;

    @Autowired
    public IdentityRecoveryNotificationFactory(
            ObjectMapper objectMapper, IdentityRecoveryNotificationProperties properties) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    IdentityRecoveryNotificationFactory(
            ObjectMapper objectMapper,
            IdentityRecoveryNotificationProperties properties,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public IdentityNotificationOutboxEvent recoveryCodesIssued(UUID accountId) {
        return create(
                accountId,
                "security.passkey.recovery-codes-issued",
                "Passkey recovery codes generated",
                "New passkey recovery codes were generated for your account.",
                "lifeos://identity/security/passkey-recovery");
    }

    public IdentityNotificationOutboxEvent recoverySucceeded(UUID accountId) {
        return create(
                accountId,
                "security.passkey.recovery-succeeded",
                "Passkey recovery sign-in",
                "A passkey recovery code was used to sign in to your account.",
                "lifeos://identity/security/sessions");
    }

    private IdentityNotificationOutboxEvent create(
            UUID accountId, String category, String title, String body, String actionUri) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        UUID eventId = UUID.randomUUID();
        UUID correlationId = correlationId();
        Instant now = clock.instant();
        NotificationRequestedV2 command = new NotificationRequestedV2(
                eventId,
                accountId,
                "personal:" + accountId,
                category,
                NotificationPriority.NORMAL,
                title,
                body,
                URI.create(actionUri),
                CHANNELS,
                now.plus(Duration.ofDays(1)),
                EVENT_TIME_ZONE);
        CloudEventV1<NotificationRequestedV2> event = new CloudEventV1<>(
                eventId,
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create(SOURCE),
                EventContract.NOTIFICATION_REQUESTED_V2_TYPE,
                "account/" + accountId,
                now,
                "application/json",
                correlationId,
                command);
        try {
            return IdentityNotificationOutboxEvent.pending(
                    eventId,
                    properties.getTopic(),
                    accountId.toString(),
                    event.type(),
                    objectMapper.writeValueAsString(event),
                    objectMapper.writeValueAsString(headers(event)),
                    now);
        } catch (JsonProcessingException exception) {
            throw new IdentityNotificationSerializationException(exception);
        }
    }

    private static UUID correlationId() {
        if (RequestContext.CORRELATION_ID.isBound()) {
            try {
                return UUID.fromString(RequestContext.CORRELATION_ID.get());
            } catch (IllegalArgumentException ignored) {
                // Request correlation IDs are validated at the HTTP boundary; a background call
                // may still use an opaque value, so use a fresh CloudEvent correlation UUID here.
            }
        }
        return UUID.randomUUID();
    }

    private static Map<String, String> headers(CloudEventV1<?> event) {
        Map<String, String> headers = new HashMap<>();
        headers.put("ce_id", event.id().toString());
        headers.put("ce_type", event.type());
        headers.put("ce_source", event.source().toString());
        headers.put("correlationid", event.correlationId().toString());
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            headers.put(
                    "traceparent",
                    "00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-"
                            + spanContext.getTraceFlags().asHex());
        }
        return Map.copyOf(headers);
    }
}
