package com.lifeos.taskgoal.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.grpc.v1.ScopedDashboardRequest;
import com.lifeos.grpc.v1.TaskMetrics;
import com.lifeos.taskgoal.task.TaskRepository;
import com.lifeos.taskgoal.task.TaskStatus;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskMetricsGrpcServiceTest {

    @Test
    void returnsBoundedOwnerScopedCounts() {
        TaskRepository repository = mock(TaskRepository.class);
        UUID accountId = UUID.randomUUID();
        when(repository.countByOwnerAccountIdAndTenantId(accountId, "personal")).thenReturn(5L);
        when(repository.countByOwnerAccountIdAndTenantIdAndStatus(accountId, "personal", TaskStatus.ACTIVE))
                .thenReturn(3L);
        when(repository.countByOwnerAccountIdAndTenantIdAndStatus(accountId, "personal", TaskStatus.COMPLETED))
                .thenReturn(2L);
        TaskMetricsGrpcService service = new TaskMetricsGrpcService(repository);
        CaptureObserver observer = new CaptureObserver();

        service.getMetrics(
                ScopedDashboardRequest.newBuilder()
                        .setAccountId(accountId.toString())
                        .setTenantId("personal")
                        .setPeriodDays(30)
                        .build(),
                observer);

        assertNotNull(observer.value);
        assertEquals(5, observer.value.getTotal());
        assertEquals(3, observer.value.getActive());
        assertEquals(2, observer.value.getCompleted());
        assertNull(observer.error);
    }

    @Test
    void rejectsInvalidScopeWithoutQueryingTheRepository() {
        TaskMetricsGrpcService service = new TaskMetricsGrpcService(mock(TaskRepository.class));
        CaptureObserver observer = new CaptureObserver();

        service.getMetrics(
                ScopedDashboardRequest.newBuilder().setAccountId("not-a-uuid").setTenantId("personal").build(), observer);

        assertNull(observer.value);
        assertNotNull(observer.error);
    }

    private static final class CaptureObserver implements StreamObserver<TaskMetrics> {
        private TaskMetrics value;
        private Throwable error;

        @Override
        public void onNext(TaskMetrics value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {}
    }
}
