package com.lifeos.finance.grpc;

import com.google.protobuf.Timestamp;
import com.lifeos.finance.domain.FinanceBudgetRepository;
import com.lifeos.finance.domain.FinancialTransactionRepository;
import com.lifeos.grpc.v1.FinanceMetrics;
import com.lifeos.grpc.v1.FinanceMetricsServiceGrpc;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Owner/tenant-scoped finance count projection for the internal dashboard boundary. */
@Service
public class FinanceMetricsGrpcService extends FinanceMetricsServiceGrpc.FinanceMetricsServiceImplBase {

    private static final int MAX_PERIOD_DAYS = 90;
    private final FinanceBudgetRepository budgetRepository;
    private final FinancialTransactionRepository transactionRepository;

    public FinanceMetricsGrpcService(
            FinanceBudgetRepository budgetRepository, FinancialTransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public void getMetrics(ScopedDashboardRequest request, StreamObserver<FinanceMetrics> observer) {
        try {
            UUID accountId = parseUuid(request.getAccountId());
            if (request.getTenantId().isBlank() || request.getPeriodDays() < 1
                    || request.getPeriodDays() > MAX_PERIOD_DAYS) {
                throw new IllegalArgumentException("dashboard scope is invalid");
            }
            long budgets = budgetRepository.countByOwnerAccountIdAndTenantId(accountId, request.getTenantId());
            long transactions = transactionRepository.countByOwnerAccountIdAndTenantId(accountId, request.getTenantId());
            Instant now = Instant.now();
            observer.onNext(FinanceMetrics.newBuilder()
                    .setBudgetCount(intBound(budgets))
                    .setTransactionCount(intBound(transactions))
                    .setStatus("ok")
                    .setObservedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("invalid dashboard scope").asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.UNAVAILABLE.withDescription("finance metrics unavailable").asRuntimeException());
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
