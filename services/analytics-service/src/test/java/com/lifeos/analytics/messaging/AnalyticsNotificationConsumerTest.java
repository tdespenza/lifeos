package com.lifeos.analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.analytics.observability.RequestContext;
import com.lifeos.analytics.projection.AnalyticsProjectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsNotificationConsumerTest {

    @Test
    void recordsProcessedAndLagForAValidEvent() {
        AnalyticsProjectionService projections = mock(AnalyticsProjectionService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UUID eventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant eventTime = Instant.now().minusSeconds(2);
        when(projections.projectNotificationRequest(
                        eq(eventId),
                        eq("com.lifeos.notification.requested.v2"),
                        eq(eventTime),
                        eq(accountId),
                        eq("personal:" + accountId)))
                .thenAnswer(invocation -> {
                    assertThat(RequestContext.CORRELATION_ID.get()).isEqualTo(correlationId.toString());
                    return true;
                });

        new AnalyticsNotificationConsumer(new ObjectMapper(), projections, registry).consume(payload(
                eventId, correlationId, eventTime, accountId));

        verify(projections).projectNotificationRequest(
                eventId,
                "com.lifeos.notification.requested.v2",
                eventTime,
                accountId,
                "personal:" + accountId);
        assertThat(registry.counter("analytics.events.processed", "event_type", "com.lifeos.notification.requested.v2")
                        .count())
                .isEqualTo(1);
        assertThat(registry.timer("analytics.events.processing_lag", "event_type", "com.lifeos.notification.requested.v2")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void recordsDuplicateWithoutProjectingAgain() {
        AnalyticsProjectionService projections = mock(AnalyticsProjectionService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UUID eventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant eventTime = Instant.now();
        when(projections.projectNotificationRequest(
                        eq(eventId), eq("com.lifeos.notification.requested.v2"), eq(eventTime), eq(accountId),
                        eq("personal:" + accountId)))
                .thenReturn(false);

        new AnalyticsNotificationConsumer(new ObjectMapper(), projections, registry).consume(payload(
                eventId, correlationId, eventTime, accountId));

        assertThat(registry.counter("analytics.events.duplicates", "event_type", "com.lifeos.notification.requested.v2")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void rejectsMalformedEventsAndCountsFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThatThrownBy(() -> new AnalyticsNotificationConsumer(
                        new ObjectMapper(), mock(AnalyticsProjectionService.class), registry)
                .consume("{\"id\":\"not-an-event\"}"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.counter("analytics.events.failures", "event_type", "com.lifeos.notification.requested.v2")
                        .count())
                .isEqualTo(1);
    }

    private static String payload(UUID eventId, UUID correlationId, Instant eventTime, UUID accountId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"com.lifeos.notification.requested.v2\","
                + "\"correlationId\":\"" + correlationId + "\","
                + "\"time\":\"" + eventTime + "\",\"data\":{\"recipientAccountId\":\""
                + accountId + "\",\"tenantId\":\"personal:" + accountId + "\"}}";
    }
}
