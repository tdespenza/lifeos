package com.lifeos.gateway.graphql;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.grpc.v1.CalendarMetrics;
import com.lifeos.grpc.v1.CalendarMetricsServiceGrpc;
import com.lifeos.grpc.v1.FinanceMetrics;
import com.lifeos.grpc.v1.FinanceMetricsServiceGrpc;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import com.lifeos.grpc.v1.TaskMetrics;
import com.lifeos.grpc.v1.TaskMetricsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bounded GraphQL dashboard client for the versioned internal gRPC metrics contracts.
 *
 * <p>The client is opt-in because certificate rotation and workload tokens are deployment-owned.
 * It never forwards the end-user bearer token to an internal gRPC host; the authenticated subject
 * facts are supplied in the request and the channel carries a separate workload credential.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.dashboard.grpc", name = "enabled", havingValue = "true")
public class DashboardGrpcClient implements AutoCloseable {

    private static final String PERSONAL_TENANT = "personal";
    private static final Metadata.Key<String> WORKLOAD_TOKEN = Metadata.Key.of(
            "x-lifeos-workload-token", Metadata.ASCII_STRING_MARSHALLER);

    private final DashboardGrpcProperties properties;
    private final ManagedChannel taskChannel;
    private final ManagedChannel calendarChannel;
    private final ManagedChannel financeChannel;

    public DashboardGrpcClient(DashboardGrpcProperties properties) {
        validate(properties);
        this.properties = properties;
        taskChannel = channel(properties.getTask());
        calendarChannel = channel(properties.getCalendar());
        financeChannel = channel(properties.getFinance());
    }

    public DashboardSnapshot fetch(GatewayAuthenticatedSubject subject, int periodDays) {
        if (subject == null) {
            throw new IllegalArgumentException("authenticated dashboard subject is required");
        }
        ScopedDashboardRequest request = ScopedDashboardRequest.newBuilder()
                .setAccountId(subject.accountId().toString())
                // GraphQL dashboard is currently the personal-account surface. Household
                // aggregation requires a future explicit tenant selector and policy descriptor.
                .setTenantId(PERSONAL_TENANT)
                .setPeriodDays(periodDays)
                .build();
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<GrpcSourceCall>awaitAllSuccessfulOrThrow(),
                configuration -> configuration.withTimeout(properties.getDeadline()))) {
            var tasks = scope.fork(() -> fetchTasks(request));
            var calendar = scope.fork(() -> fetchCalendar(request));
            var finance = scope.fork(() -> fetchFinance(request));
            scope.join();
            return snapshot(periodDays, tasks.get(), calendar.get(), finance.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailableSnapshot(periodDays);
        } catch (StructuredTaskScope.TimeoutException exception) {
            return unavailableSnapshot(periodDays);
        }
    }

    private GrpcSourceCall fetchTasks(ScopedDashboardRequest request) {
        Result<TaskMetrics> result = call(
                () -> TaskMetricsServiceGrpc.newBlockingStub(taskChannel)
                        .withInterceptors(workloadInterceptor(properties.getTask().getWorkloadToken()))
                        .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS)
                        .getMetrics(request));
        return new GrpcSourceCall("tasks", result.value(), result.latencyMillis());
    }

    private GrpcSourceCall fetchCalendar(ScopedDashboardRequest request) {
        Result<CalendarMetrics> result = call(
                () -> CalendarMetricsServiceGrpc.newBlockingStub(calendarChannel)
                        .withInterceptors(workloadInterceptor(properties.getCalendar().getWorkloadToken()))
                        .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS)
                        .getMetrics(request));
        return new GrpcSourceCall("calendar", result.value(), result.latencyMillis());
    }

    private GrpcSourceCall fetchFinance(ScopedDashboardRequest request) {
        Result<FinanceMetrics> result = call(
                () -> FinanceMetricsServiceGrpc.newBlockingStub(financeChannel)
                        .withInterceptors(workloadInterceptor(properties.getFinance().getWorkloadToken()))
                        .withDeadlineAfter(properties.getDeadline().toMillis(), TimeUnit.MILLISECONDS)
                        .getMetrics(request));
        return new GrpcSourceCall("finance", result.value(), result.latencyMillis());
    }

    private static DashboardSnapshot snapshot(
            int periodDays, GrpcSourceCall taskCall, GrpcSourceCall calendarCall, GrpcSourceCall financeCall) {
        List<DashboardSnapshot.SourceStatus> sources = List.of(
                taskCall.status(), calendarCall.status(), financeCall.status());
        DashboardSnapshot.TaskMetrics tasks = taskCall.value() instanceof TaskMetrics metrics
                ? new DashboardSnapshot.TaskMetrics(metrics.getTotal(), metrics.getActive(), metrics.getCompleted())
                : new DashboardSnapshot.TaskMetrics(0, 0, 0);
        DashboardSnapshot.CalendarMetrics calendar = calendarCall.value() instanceof CalendarMetrics metrics
                ? new DashboardSnapshot.CalendarMetrics(metrics.getEventCount(), "AVAILABLE")
                : new DashboardSnapshot.CalendarMetrics(0, "UNAVAILABLE");
        DashboardSnapshot.FinanceMetrics finance = financeCall.value() instanceof FinanceMetrics metrics
                ? new DashboardSnapshot.FinanceMetrics(
                        metrics.getBudgetCount(), metrics.getTransactionCount(), "AVAILABLE")
                : new DashboardSnapshot.FinanceMetrics(0, 0, "UNAVAILABLE");
        long available = sources.stream().filter(source -> "AVAILABLE".equals(source.status())).count();
        DashboardSnapshot.AggregateStatus status = available == sources.size()
                ? DashboardSnapshot.AggregateStatus.COMPLETE
                : available == 0 ? DashboardSnapshot.AggregateStatus.UNAVAILABLE : DashboardSnapshot.AggregateStatus.PARTIAL;
        return new DashboardSnapshot(Instant.now().toString(), periodDays, status, tasks, calendar, finance, sources);
    }

    private static DashboardSnapshot unavailableSnapshot(int periodDays) {
        return snapshot(
                periodDays,
                new GrpcSourceCall("tasks", null, 0),
                new GrpcSourceCall("calendar", null, 0),
                new GrpcSourceCall("finance", null, 0));
    }

    @Override
    public void close() {
        taskChannel.shutdown();
        calendarChannel.shutdown();
        financeChannel.shutdown();
        try {
            taskChannel.awaitTermination(1, TimeUnit.SECONDS);
            calendarChannel.awaitTermination(1, TimeUnit.SECONDS);
            financeChannel.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static io.grpc.ClientInterceptor workloadInterceptor(String workloadToken) {
        Metadata headers = new Metadata();
        headers.put(WORKLOAD_TOKEN, workloadToken);
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }

    private static <T> Result<T> call(Callable<T> operation) {
        long started = System.nanoTime();
        try {
            return new Result<>(operation.call(), elapsedMillis(started));
        } catch (RuntimeException exception) {
            return new Result<>(null, elapsedMillis(started));
        } catch (Exception exception) {
            return new Result<>(null, elapsedMillis(started));
        }
    }

    private ManagedChannel channel(DashboardGrpcProperties.Service service) {
        try {
            var builder = NettyChannelBuilder.forAddress(service.getHost(), service.getPort())
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(5, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(false);
            builder.sslContext(GrpcSslContexts.forClient()
                    .trustManager(new File(service.getTrustCertificateCollection()))
                    .keyManager(new File(service.getCertificateChain()), new File(service.getPrivateKey()))
                    .build());
            return builder.build();
        } catch (IOException exception) {
            throw new IllegalStateException("dashboard gRPC mTLS client failed to initialize", exception);
        }
    }

    private static void validate(DashboardGrpcProperties properties) {
        if (properties == null
                || properties.getDeadline() == null
                || properties.getDeadline().isNegative()
                || properties.getDeadline().isZero()
                || properties.getDeadline().compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("dashboard gRPC deadline must be between 1ms and 10s");
        }
        validateService(properties.getTask());
        validateService(properties.getCalendar());
        validateService(properties.getFinance());
    }

    private static void validateService(DashboardGrpcProperties.Service service) {
        if (service == null
                || service.getHost() == null
                || service.getHost().isBlank()
                || service.getPort() < 1_024
                || service.getPort() > 65_535
                || !service.isTlsEnabled()
                || service.getCertificateChain().isBlank()
                || service.getPrivateKey().isBlank()
                || service.getTrustCertificateCollection().isBlank()
                || service.getWorkloadToken().isBlank()) {
            throw new IllegalStateException(
                    "enabled dashboard gRPC requires mTLS files and a workload token for every source");
        }
    }

    private static int elapsedMillis(long started) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return (int) Math.max(0, Math.min(120_000, elapsed));
    }

    private record Result<T>(T value, int latencyMillis) {
    }

    private record GrpcSourceCall(String source, Object value, int latencyMillis) {

        DashboardSnapshot.SourceStatus status() {
            return new DashboardSnapshot.SourceStatus(
                    source, value == null ? "UNAVAILABLE" : "AVAILABLE", latencyMillis);
        }
    }
}
