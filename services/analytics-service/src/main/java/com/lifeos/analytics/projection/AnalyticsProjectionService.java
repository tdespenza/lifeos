package com.lifeos.analytics.projection;

import com.lifeos.analytics.config.AnalyticsProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintains deterministic per-account dashboard snapshots with bounded keys and values. */
@Service
public class AnalyticsProjectionService {

    private static final String SOURCE_VERSION = "analytics-v1";
    private static final String NOTIFICATION_REQUESTED = "notifications.requested";

    private final AnalyticsMetricSnapshotRepository snapshots;
    private final AnalyticsMetricHistoryRepository history;
    private final AnalyticsEventInboxRepository inbox;
    private final AnalyticsProperties properties;

    public AnalyticsProjectionService(
            AnalyticsMetricSnapshotRepository snapshots,
            AnalyticsMetricHistoryRepository history,
            AnalyticsEventInboxRepository inbox,
            AnalyticsProperties properties) {
        this.snapshots = snapshots;
        this.history = history;
        this.inbox = inbox;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<AnalyticsMetricSnapshot> dashboard(UUID ownerAccountId, String tenantId, int periodDays) {
        validateScope(ownerAccountId, tenantId);
        validatePeriod(periodDays);
        return snapshots.findAllByOwnerAccountIdAndTenantIdAndPeriodDaysOrderByMetricKeyAsc(
                ownerAccountId, tenantId, periodDays);
    }

    /**
     * Derives a small, deterministic productivity read model from already persisted metrics. It
     * never calls an AI provider, extrapolates missing data, or persists recommendations.
     */
    @Transactional(readOnly = true)
    public List<ProductivityInsight> productivityInsights(UUID ownerAccountId, String tenantId, int periodDays) {
        Map<String, Long> metrics = new HashMap<>();
        dashboard(ownerAccountId, tenantId, periodDays).forEach(snapshot ->
                metrics.put(snapshot.getMetricKey(), snapshot.getMetricValue()));
        List<ProductivityInsight> insights = new ArrayList<>();
        Long created = metrics.get("tasks.created");
        Long completed = metrics.get("tasks.completed");
        if (created != null && completed != null && created > 0) {
            long boundedCompleted = Math.min(completed, created);
            // Avoid multiplying two bounded long values: even the documented upper bound can
            // overflow before the result is capped at 100. A ratio in [0, 1] is safe here.
            int score = (int) Math.min(100L, Math.round(((double) boundedCompleted / (double) created) * 100.0d));
            insights.add(new ProductivityInsight(
                    score >= 80 ? "task-follow-through" : "task-completion-opportunity",
                    score,
                    List.of("tasks.created", "tasks.completed"),
                    "analytics-v1"));
        }
        Long focusMinutes = metrics.get("focus.minutes");
        if (focusMinutes != null && focusMinutes > 0) {
            int score = (int) Math.min(100L, focusMinutes / 6L);
            insights.add(new ProductivityInsight("focus-time", score, List.of("focus.minutes"), "analytics-v1"));
        }
        return insights.stream()
                .sorted(Comparator.comparing(ProductivityInsight::key))
                .limit(5)
                .toList();
    }

    @Transactional
    public void record(UUID ownerAccountId, String tenantId, String metricKey, long value, int periodDays) {
        validateScope(ownerAccountId, tenantId);
        validateMetric(metricKey);
        validatePeriod(periodDays);
        if (value < 0 || value > Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("metric value is outside the bounded non-negative range");
        }
        AnalyticsMetricSnapshot snapshot = snapshots
                .findByOwnerAccountIdAndTenantIdAndMetricKeyAndPeriodDays(ownerAccountId, tenantId, metricKey, periodDays)
                .orElseGet(() -> new AnalyticsMetricSnapshot(
                        ownerAccountId, tenantId, metricKey, value, periodDays, Instant.now(), SOURCE_VERSION));
        if (snapshot.getId() != null && snapshot.getMetricValue() != value) {
            snapshot.replaceValue(value, Instant.now(), SOURCE_VERSION);
        }
        snapshots.save(snapshot);
        recordHistory(ownerAccountId, tenantId, metricKey, periodDays, value);
        enforceSnapshotBound(ownerAccountId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<AnalyticsMetricHistory> trend(
            UUID ownerAccountId, String tenantId, String metricKey, int periodDays, int days) {
        validateScope(ownerAccountId, tenantId);
        validateMetric(metricKey);
        validatePeriod(periodDays);
        if (days < 1 || days > 90) {
            throw new IllegalArgumentException("days must be between 1 and 90");
        }
        LocalDate end = LocalDate.now(ZoneOffset.UTC);
        return history.findAllByOwnerAccountIdAndTenantIdAndMetricKeyAndPeriodDaysAndObservationDateBetweenOrderByObservationDateAsc(
                ownerAccountId, tenantId, metricKey, periodDays, end.minusDays(days - 1L), end);
    }

    @Transactional
    public boolean acceptEvent(UUID eventId, String eventType) {
        if (eventId == null || eventType == null || eventType.isBlank() || eventType.length() > 200) {
            throw new IllegalArgumentException("event identity is invalid");
        }
        if (inbox.existsById(eventId)) {
            return false;
        }
        try {
            inbox.saveAndFlush(new AnalyticsEventInbox(eventId, eventType, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException race) {
            return false;
        }
    }

    /**
     * Atomically reserves and projects a notification event. A failed projection rolls back the
     * inbox reservation so Kafka can retry it; a duplicate that was already committed is a no-op.
     */
    @Transactional(timeout = 5)
    public boolean projectNotificationRequest(
            UUID eventId,
            String eventType,
            Instant eventTime,
            UUID recipientAccountId,
            String tenantId) {
        if (eventId == null || eventType == null || eventType.isBlank() || eventType.length() > 200
                || eventTime == null) {
            throw new IllegalArgumentException("event identity is invalid");
        }
        if (inbox.existsById(eventId)) {
            return false;
        }
        inbox.save(new AnalyticsEventInbox(eventId, eventType, Instant.now()));
        recordNotificationRequest(recipientAccountId, tenantId);
        return true;
    }

    @Transactional
    public void recordNotificationRequest(UUID ownerAccountId, String tenantId) {
        validateScope(ownerAccountId, tenantId);
        int periodDays = properties.getDefaultPeriodDays();
        if (snapshots.increment(ownerAccountId, tenantId, NOTIFICATION_REQUESTED, periodDays) == 0) {
            try {
                snapshots.saveAndFlush(new AnalyticsMetricSnapshot(
                        ownerAccountId, tenantId, NOTIFICATION_REQUESTED, 1, periodDays, Instant.now(), SOURCE_VERSION));
            } catch (DataIntegrityViolationException race) {
                // A concurrent first event created the unique row; the second update is now atomic.
                snapshots.increment(ownerAccountId, tenantId, NOTIFICATION_REQUESTED, periodDays);
            }
        }
    }

    private void enforceSnapshotBound(UUID ownerAccountId, String tenantId) {
        long count = snapshots.findAllByOwnerAccountIdAndTenantIdAndPeriodDaysOrderByMetricKeyAsc(
                ownerAccountId, tenantId, properties.getDefaultPeriodDays()).size();
        if (count > properties.getMaxSnapshots()) {
            throw new IllegalStateException("analytics snapshot bound exceeded");
        }
    }

    private void recordHistory(
            UUID ownerAccountId, String tenantId, String metricKey, int periodDays, long value) {
        LocalDate observationDate = LocalDate.now(ZoneOffset.UTC);
        AnalyticsMetricHistory observation = history
                .findByOwnerAccountIdAndTenantIdAndMetricKeyAndPeriodDaysAndObservationDate(
                        ownerAccountId, tenantId, metricKey, periodDays, observationDate)
                .orElseGet(() -> new AnalyticsMetricHistory(
                        ownerAccountId,
                        tenantId,
                        metricKey,
                        periodDays,
                        observationDate,
                        value,
                        SOURCE_VERSION));
        if (observation.getId() != null && observation.getMetricValue() != value) {
            observation.replaceValue(value, SOURCE_VERSION);
        }
        history.save(observation);
    }

    private static void validateScope(UUID ownerAccountId, String tenantId) {
        if (ownerAccountId == null || tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("analytics scope is invalid");
        }
    }

    private static void validateMetric(String metricKey) {
        if (metricKey == null || !metricKey.matches("[a-z][a-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("metric key is invalid");
        }
    }

    private static void validatePeriod(int periodDays) {
        if (periodDays < 1 || periodDays > 90) {
            throw new IllegalArgumentException("periodDays must be between 1 and 90");
        }
    }

    public record ProductivityInsight(String key, int score, List<String> evidenceKeys, String sourceVersion) {
        public ProductivityInsight {
            if (key == null || !key.matches("[a-z][a-z0-9-]{0,63}")
                    || score < 0 || score > 100 || evidenceKeys == null || evidenceKeys.isEmpty()
                    || evidenceKeys.size() > 5 || evidenceKeys.stream().anyMatch(value -> value == null || value.isBlank())
                    || sourceVersion == null || sourceVersion.length() > 32) {
                throw new IllegalArgumentException("productivity insight is invalid or unbounded");
            }
            evidenceKeys = List.copyOf(evidenceKeys);
        }
    }
}
