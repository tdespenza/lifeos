package com.lifeos.taskgoal.grpc;

import com.google.protobuf.Timestamp;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import com.lifeos.grpc.v1.TaskMetrics;
import com.lifeos.grpc.v1.TaskMetricsServiceGrpc;
import com.lifeos.taskgoal.task.TaskRepository;
import com.lifeos.taskgoal.task.TaskStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Workload-authenticated, owner-scoped Task metrics endpoint for the GraphQL/gRPC boundary. */
@Service
public class TaskMetricsGrpcService extends TaskMetricsServiceGrpc.TaskMetricsServiceImplBase {

    private static final int MAX_PERIOD_DAYS = 90;

    private final TaskRepository taskRepository;

    public TaskMetricsGrpcService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public void getMetrics(ScopedDashboardRequest request, StreamObserver<TaskMetrics> observer) {
        try {
            UUID accountId = parseUuid(request.getAccountId());
            String tenantId = request.getTenantId();
            if (tenantId.isBlank() || request.getPeriodDays() < 1 || request.getPeriodDays() > MAX_PERIOD_DAYS) {
                throw new IllegalArgumentException("dashboard scope is invalid");
            }
            long total = taskRepository.countByOwnerAccountIdAndTenantId(accountId, tenantId);
            long active = taskRepository.countByOwnerAccountIdAndTenantIdAndStatus(accountId, tenantId, TaskStatus.ACTIVE);
            long completed = taskRepository.countByOwnerAccountIdAndTenantIdAndStatus(
                    accountId, tenantId, TaskStatus.COMPLETED);
            Instant now = Instant.now();
            Timestamp observedAt = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
            observer.onNext(TaskMetrics.newBuilder()
                    .setTotal(intBound(total))
                    .setActive(intBound(active))
                    .setCompleted(intBound(completed))
                    .setObservedAt(observedAt)
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("invalid dashboard scope").asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.UNAVAILABLE.withDescription("task metrics unavailable").asRuntimeException());
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("account id is required");
        }
        return UUID.fromString(value);
    }

    private static int intBound(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }
}
