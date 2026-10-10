package com.lifeos.gateway.graphql;

import java.time.Instant;
import java.util.List;

/** Immutable GraphQL dashboard response with explicit partial-source status. */
public record DashboardSnapshot(
        String generatedAt,
        int periodDays,
        AggregateStatus status,
        TaskMetrics tasks,
        CalendarMetrics calendar,
        FinanceMetrics finance,
        List<SourceStatus> sources) {

    public DashboardSnapshot {
        generatedAt = generatedAt == null ? Instant.now().toString() : generatedAt;
        if (periodDays < 1 || periodDays > 90) {
            throw new IllegalArgumentException("periodDays must be between 1 and 90");
        }
        status = status == null ? AggregateStatus.UNAVAILABLE : status;
        tasks = tasks == null ? new TaskMetrics(0, 0, 0) : tasks;
        calendar = calendar == null ? new CalendarMetrics(0, "UNAVAILABLE") : calendar;
        finance = finance == null ? new FinanceMetrics(0, 0, "UNAVAILABLE") : finance;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public enum AggregateStatus {
        COMPLETE,
        PARTIAL,
        UNAVAILABLE
    }

    public record SourceStatus(String source, String status, int latencyMillis) {
        public SourceStatus {
            if (source == null || source.isBlank() || source.length() > 32) {
                throw new IllegalArgumentException("source must be bounded and non-blank");
            }
            if (status == null || status.isBlank() || status.length() > 32) {
                throw new IllegalArgumentException("status must be bounded and non-blank");
            }
            if (latencyMillis < 0 || latencyMillis > 120_000) {
                throw new IllegalArgumentException("latencyMillis must be bounded");
            }
        }
    }

    public record TaskMetrics(int total, int active, int completed) {
        public TaskMetrics {
            if (total < 0 || active < 0 || completed < 0 || active > total || completed > total) {
                throw new IllegalArgumentException("task metrics must be non-negative and consistent");
            }
        }
    }

    public record CalendarMetrics(int eventCount, String status) {
        public CalendarMetrics {
            if (eventCount < 0 || eventCount > 100_000) {
                throw new IllegalArgumentException("eventCount must be bounded");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("calendar status must be non-blank");
            }
        }
    }

    public record FinanceMetrics(int budgetCount, int transactionCount, String status) {
        public FinanceMetrics {
            if (budgetCount < 0 || budgetCount > 100_000 || transactionCount < 0 || transactionCount > 100_000) {
                throw new IllegalArgumentException("finance metrics must be bounded");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("finance status must be non-blank");
            }
        }
    }
}
