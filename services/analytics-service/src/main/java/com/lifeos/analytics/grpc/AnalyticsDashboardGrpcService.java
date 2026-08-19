package com.lifeos.analytics.grpc;

import com.lifeos.analytics.config.AnalyticsProperties;
import com.lifeos.analytics.projection.AnalyticsMetricSnapshot;
import com.lifeos.analytics.projection.AnalyticsMetricSnapshotRepository;
import com.lifeos.grpc.analytics.v1.DashboardAggregationServiceGrpc;
import com.lifeos.grpc.analytics.v1.GetDashboardSnapshotRequest;
import com.lifeos.grpc.analytics.v1.GetDashboardSnapshotResponse;
import com.lifeos.grpc.analytics.v1.Metric;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Bounded owner-scoped Analytics dashboard projection for internal gRPC consumers. */
@Service
public class AnalyticsDashboardGrpcService
        extends DashboardAggregationServiceGrpc.DashboardAggregationServiceImplBase {

    private static final int MAX_METRICS = 100;
    private final AnalyticsMetricSnapshotRepository snapshots;
    private final AnalyticsProperties properties;

    public AnalyticsDashboardGrpcService(
            AnalyticsMetricSnapshotRepository snapshots, AnalyticsProperties properties) {
        this.snapshots = snapshots;
        this.properties = properties;
    }

    @Override
    public void getDashboardSnapshot(
            GetDashboardSnapshotRequest request, StreamObserver<GetDashboardSnapshotResponse> observer) {
        try {
            UUID account = parseUuid(request.getOwnerAccountId());
            String tenant = "personal:" + account;
            var rows = snapshots.findAllByOwnerAccountIdAndTenantIdAndPeriodDaysOrderByMetricKeyAsc(
                    account, tenant, properties.getDefaultPeriodDays());
            if (rows.size() > MAX_METRICS) {
                throw new IllegalStateException("analytics metric bound exceeded");
            }
            GetDashboardSnapshotResponse.Builder response = GetDashboardSnapshotResponse.newBuilder()
                    .setSnapshotVersion("analytics-v1");
            for (AnalyticsMetricSnapshot row : rows) {
                response.addMetrics(Metric.newBuilder()
                        .setName(row.getMetricKey())
                        .setValue(Long.toString(row.getMetricValue()))
                        .setFreshness(row.getObservedAt().toString())
                        .build());
            }
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("invalid dashboard scope").asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.UNAVAILABLE.withDescription("analytics metrics unavailable").asRuntimeException());
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("owner account id is required");
        }
        return UUID.fromString(value);
    }
}
