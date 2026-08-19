package com.lifeos.calendar.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.calendar.domain.CalendarOutboxEvent;
import com.lifeos.calendar.domain.CalendarReminder;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV2;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds Calendar's privacy-safe V2 reminder command from persisted, non-sensitive reminder facts. */
@Component
public class CalendarReminderNotificationOutboxFactory {

    private static final String GENERIC_TITLE = "Calendar reminder";
    private static final String GENERIC_BODY = "An upcoming calendar event is starting soon.";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CalendarReminderNotificationOutboxFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Returns an immutable outbox record; it never reads or serializes the event's private title/location. */
    public CalendarOutboxEvent create(CalendarReminder reminder, long aggregateVersion) {
        NotificationRequestedV2 command = new NotificationRequestedV2(
                reminder.getNotificationEventId(),
                reminder.getOwnerAccountId(),
                reminder.getTenantId(),
                "calendar.reminder",
                NotificationPriority.NORMAL,
                GENERIC_TITLE,
                GENERIC_BODY,
                URI.create("lifeos://calendar/events/" + reminder.getEventId()),
                reminder.channels(),
                reminder.getDueAt().plusSeconds((long) reminder.getMinutesBefore() * 60L),
                reminder.getEventTimeZone());
        CloudEventV1<NotificationRequestedV2> event = new CloudEventV1<>(
                reminder.getNotificationEventId(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:calendar-service"),
                EventContract.NOTIFICATION_REQUESTED_V2_TYPE,
                "notification/" + reminder.getNotificationEventId(),
                clock.instant(),
                "application/json",
                reminder.getCorrelationId(),
                command);
        try {
            String payload = objectMapper.writeValueAsString(event);
            String headers = objectMapper.writeValueAsString(headers(event));
            return CalendarOutboxEvent.pending(
                    event.id(),
                    reminder.getId(),
                    reminder.getEventId(),
                    aggregateVersion,
                    event.type(),
                    EventContract.NOTIFICATION_REQUESTED_V2_TOPIC,
                    reminder.getOwnerAccountId().toString(),
                    payload,
                    headers,
                    clock.instant());
        } catch (Exception exception) {
            throw new CalendarOutboxSerializationException(exception);
        }
    }

    private static Map<String, String> headers(CloudEventV1<NotificationRequestedV2> event) {
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
