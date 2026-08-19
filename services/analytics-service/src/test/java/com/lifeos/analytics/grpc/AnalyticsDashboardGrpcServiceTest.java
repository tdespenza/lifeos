package com.lifeos.analytics.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lifeos.analytics.config.AnalyticsProperties;
import com.lifeos.analytics.projection.AnalyticsMetricSnapshot;
import com.lifeos.analytics.projection.AnalyticsMetricSnapshotRepository;
import com.lifeos.grpc.analytics.v1.GetDashboardSnapshotRequest;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AnalyticsDashboardGrpcServiceTest {

    @Test
    void returnsOnlyPersonalOwnerScopedMetrics() {
        AnalyticsMetricSnapshotRepository repository = Mockito.mock(AnalyticsMetricSnapshotRepository.class);
        AnalyticsProperties properties = new AnalyticsProperties();
        properties.setDefaultPeriodDays(30);
        UUID account = UUID.randomUUID();
        when(repository.findAllByOwnerAccountIdAndTenantIdAndPeriodDaysOrderByMetricKeyAsc(
                        account, "personal:" + account, 30))
                .thenReturn(List.of(new AnalyticsMetricSnapshot(
                        account, "personal:" + account, "tasks.completed", 4, 30, Instant.now(), "analytics-v1")));
        AnalyticsDashboardGrpcService service = new AnalyticsDashboardGrpcService(repository, properties);
        CapturingObserver observer = new CapturingObserver();

        service.getDashboardSnapshot(
                GetDashboardSnapshotRequest.newBuilder().setOwnerAccountId(account.toString()).build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.response.getMetricsList()).singleElement()
                .satisfies(metric -> assertThat(metric.getName()).isEqualTo("tasks.completed"));
        assertThat(observer.response.getMetrics(0).getValue()).isEqualTo("4");
    }

    @Test
    void rejectsMissingOwnerAccount() {
        AnalyticsDashboardGrpcService service = new AnalyticsDashboardGrpcService(
                Mockito.mock(AnalyticsMetricSnapshotRepository.class), new AnalyticsProperties());
        CapturingObserver observer = new CapturingObserver();

        service.getDashboardSnapshot(GetDashboardSnapshotRequest.getDefaultInstance(), observer);

        assertThat(observer.response).isNull();
        assertThat(observer.error).isNotNull();
    }

    private static final class CapturingObserver implements StreamObserver<com.lifeos.grpc.analytics.v1.GetDashboardSnapshotResponse> {
        private com.lifeos.grpc.analytics.v1.GetDashboardSnapshotResponse response;
        private Throwable error;
        @Override public void onNext(com.lifeos.grpc.analytics.v1.GetDashboardSnapshotResponse value) { response = value; }
        @Override public void onError(Throwable throwable) { error = throwable; }
        @Override public void onCompleted() {}
    }
}
