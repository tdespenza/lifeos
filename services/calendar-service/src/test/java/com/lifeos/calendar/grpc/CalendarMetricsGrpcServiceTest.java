package com.lifeos.calendar.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.grpc.v1.CalendarMetrics;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarMetricsGrpcServiceTest {

    @Test
    void returnsOwnerScopedCount() {
        CalendarEventRepository repository = mock(CalendarEventRepository.class);
        UUID accountId = UUID.randomUUID();
        when(repository.countByTenantIdAndOwnerAccountId("personal", accountId)).thenReturn(7L);
        CaptureObserver observer = new CaptureObserver();

        new CalendarMetricsGrpcService(repository).getMetrics(
                ScopedDashboardRequest.newBuilder().setAccountId(accountId.toString()).setTenantId("personal")
                        .setPeriodDays(30).build(), observer);

        assertNotNull(observer.value);
        assertEquals(7, observer.value.getEventCount());
        assertEquals("ok", observer.value.getStatus());
        assertNull(observer.error);
    }

    @Test
    void rejectsInvalidScope() {
        CaptureObserver observer = new CaptureObserver();
        new CalendarMetricsGrpcService(mock(CalendarEventRepository.class)).getMetrics(
                ScopedDashboardRequest.newBuilder().setAccountId("not-a-uuid").setTenantId("personal").build(), observer);

        assertNull(observer.value);
        assertNotNull(observer.error);
    }

    private static final class CaptureObserver implements StreamObserver<CalendarMetrics> {
        private CalendarMetrics value;
        private Throwable error;

        @Override public void onNext(CalendarMetrics value) { this.value = value; }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onCompleted() { }
    }
}
