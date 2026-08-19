package com.lifeos.calendar.grpc;

import com.google.protobuf.Timestamp;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.grpc.v1.CalendarMetrics;
import com.lifeos.grpc.v1.CalendarMetricsServiceGrpc;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owner/tenant-scoped calendar count projection for the internal dashboard boundary. */
@Service
public class CalendarMetricsGrpcService extends CalendarMetricsServiceGrpc.CalendarMetricsServiceImplBase {

    private static final int MAX_PERIOD_DAYS = 90;
    private final CalendarEventRepository eventRepository;

    public CalendarMetricsGrpcService(CalendarEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public void getMetrics(ScopedDashboardRequest request, StreamObserver<CalendarMetrics> observer) {
        try {
            UUID accountId = parseUuid(request.getAccountId());
            if (request.getTenantId().isBlank() || request.getPeriodDays() < 1
                    || request.getPeriodDays() > MAX_PERIOD_DAYS) {
                throw new IllegalArgumentException("dashboard scope is invalid");
            }
            long count = eventRepository.countByTenantIdAndOwnerAccountId(request.getTenantId(), accountId);
            Instant now = Instant.now();
            observer.onNext(CalendarMetrics.newBuilder()
                    .setEventCount(intBound(count))
                    .setStatus("ok")
                    .setObservedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("invalid dashboard scope").asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.UNAVAILABLE.withDescription("calendar metrics unavailable").asRuntimeException());
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("account id is required");
        return UUID.fromString(value);
    }

    private static int intBound(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }
}
