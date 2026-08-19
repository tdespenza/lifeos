package com.lifeos.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DashboardSnapshotTest {

    @Test
    void clampsNothingAndPreservesExplicitPartialSourceStatus() {
        DashboardSnapshot snapshot = new DashboardSnapshot(
                "2026-08-18T00:00:00Z",
                30,
                DashboardSnapshot.AggregateStatus.PARTIAL,
                new DashboardSnapshot.TaskMetrics(3, 2, 1),
                new DashboardSnapshot.CalendarMetrics(0, "UNAVAILABLE"),
                new DashboardSnapshot.FinanceMetrics(1, 4, "AVAILABLE"),
                java.util.List.of(new DashboardSnapshot.SourceStatus("tasks", "AVAILABLE", 12)));

        assertThat(snapshot.status()).isEqualTo(DashboardSnapshot.AggregateStatus.PARTIAL);
        assertThat(snapshot.tasks().active()).isEqualTo(2);
        assertThat(snapshot.sources()).hasSize(1);
    }

    @Test
    void rejectsUnboundedPeriodAndInconsistentTaskCounts() {
        assertThatThrownBy(() -> new DashboardSnapshot(
                null, 91, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardSnapshot.TaskMetrics(1, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
