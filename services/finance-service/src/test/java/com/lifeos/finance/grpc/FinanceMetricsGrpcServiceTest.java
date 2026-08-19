package com.lifeos.finance.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.finance.domain.FinanceBudgetRepository;
import com.lifeos.finance.domain.FinancialTransactionRepository;
import com.lifeos.grpc.v1.FinanceMetrics;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinanceMetricsGrpcServiceTest {

    @Test
    void returnsOwnerScopedCounts() {
        FinanceBudgetRepository budgets = mock(FinanceBudgetRepository.class);
        FinancialTransactionRepository transactions = mock(FinancialTransactionRepository.class);
        UUID accountId = UUID.randomUUID();
        when(budgets.countByOwnerAccountIdAndTenantId(accountId, "personal")).thenReturn(2L);
        when(transactions.countByOwnerAccountIdAndTenantId(accountId, "personal")).thenReturn(11L);
        CaptureObserver observer = new CaptureObserver();

        new FinanceMetricsGrpcService(budgets, transactions).getMetrics(
                ScopedDashboardRequest.newBuilder().setAccountId(accountId.toString()).setTenantId("personal")
                        .setPeriodDays(30).build(), observer);

        assertNotNull(observer.value);
        assertEquals(2, observer.value.getBudgetCount());
        assertEquals(11, observer.value.getTransactionCount());
        assertNull(observer.error);
    }

    @Test
    void rejectsInvalidScope() {
        CaptureObserver observer = new CaptureObserver();
        new FinanceMetricsGrpcService(mock(FinanceBudgetRepository.class), mock(FinancialTransactionRepository.class))
                .getMetrics(ScopedDashboardRequest.newBuilder().setAccountId("bad").setTenantId("personal").build(), observer);

        assertNull(observer.value);
        assertNotNull(observer.error);
    }

    private static final class CaptureObserver implements StreamObserver<FinanceMetrics> {
        private FinanceMetrics value;
        private Throwable error;

        @Override public void onNext(FinanceMetrics value) { this.value = value; }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onCompleted() { }
    }
}
