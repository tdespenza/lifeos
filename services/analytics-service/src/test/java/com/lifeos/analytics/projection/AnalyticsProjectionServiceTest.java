package com.lifeos.analytics.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AnalyticsProjectionServiceTest {

    @Autowired
    private AnalyticsProjectionService projections;

    @Test
    void recordsAndReplacesBoundedSnapshot() {
        UUID account = UUID.randomUUID();
        projections.record(account, "personal:" + account, "tasks.completed", 3, 7);
        projections.record(account, "personal:" + account, "tasks.completed", 5, 7);

        assertThat(projections.dashboard(account, "personal:" + account, 7))
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.getMetricValue()).isEqualTo(5));
    }

    @Test
    void rejectsUnboundedMetricKeyAndPeriod() {
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> projections.record(account, "personal:" + account, "Bad Key", 1, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projections.record(account, "personal:" + account, "tasks.done", 1, 91))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deduplicatesEventIds() {
        UUID eventId = UUID.randomUUID();
        assertThat(projections.acceptEvent(eventId, "com.lifeos.notification.requested.v2")).isTrue();
        assertThat(projections.acceptEvent(eventId, "com.lifeos.notification.requested.v2")).isFalse();
    }

    @Test
    void incrementsEventProjectionWithoutReadModifyWriteLoss() {
        UUID account = UUID.randomUUID();
        String tenant = "personal:" + account;
        projections.recordNotificationRequest(account, tenant);
        projections.recordNotificationRequest(account, tenant);

        assertThat(projections.dashboard(account, tenant, 30))
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.getMetricValue()).isEqualTo(2));
    }

    @Test
    void rollsBackInboxReservationWhenProjectionValidationFails() {
        UUID eventId = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> projections.projectNotificationRequest(
                        eventId,
                        "com.lifeos.notification.requested.v2",
                        java.time.Instant.now(),
                        account,
                        " "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(projections.projectNotificationRequest(
                        eventId,
                        "com.lifeos.notification.requested.v2",
                        java.time.Instant.now(),
                        account,
                        "personal:" + account))
                .isTrue();
    }

    @Test
    void derivesProductivityInsightsOnlyWhenEvidenceExists() {
        UUID account = UUID.randomUUID();
        String tenant = "personal:" + account;
        projections.record(account, tenant, "tasks.created", 10, 30);
        projections.record(account, tenant, "tasks.completed", 8, 30);
        projections.record(account, tenant, "focus.minutes", 120, 30);

        assertThat(projections.productivityInsights(account, tenant, 30))
                .extracting(AnalyticsProjectionService.ProductivityInsight::key)
                .containsExactly("focus-time", "task-follow-through");
        assertThat(projections.productivityInsights(UUID.randomUUID(), "personal:missing", 30)).isEmpty();
    }

    @Test
    void computesLargeMetricRatiosWithoutOverflow() {
        UUID account = UUID.randomUUID();
        String tenant = "personal:" + account;
        long created = Long.MAX_VALUE / 2;
        projections.record(account, tenant, "tasks.created", created, 30);
        projections.record(account, tenant, "tasks.completed", created, 30);

        assertThat(projections.productivityInsights(account, tenant, 30))
                .singleElement()
                .satisfies(insight -> assertThat(insight.score()).isEqualTo(100));
    }
}
