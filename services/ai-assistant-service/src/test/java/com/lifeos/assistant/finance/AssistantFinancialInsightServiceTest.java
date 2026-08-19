package com.lifeos.assistant.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantFinancialInsightServiceTest {

    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));

    @Mock
    private AssistantFinanceClient financeClient;

    @Mock
    private AssistantAuditService auditService;

    @Test
    void returnsBoundedAggregateWithoutRawTransactions() {
        when(financeClient.insights(SUBJECT, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-18"), "USD"))
                .thenReturn(new AssistantFinanceClient.FinancialInsightSnapshot(
                        "USD",
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-18"),
                        100_00,
                        40_00,
                        60_00,
                        List.of(new AssistantFinanceClient.Category("food", 0, 40_00, -40_00)),
                        false,
                        List.of("NO_FX_CONVERSION")));

        AssistantFinancialInsightService.FinancialInsight result = new AssistantFinancialInsightService(
                financeClient, auditService).insight(
                        SUBJECT,
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-18"),
                        "USD");

        assertThat(result.netMinor()).isEqualTo(60_00);
        assertThat(result.categories()).hasSize(1);
        verify(auditService).record(any());
    }

    @Test
    void rejectsRangesLongerThanTheFinanceBound() {
        assertThatThrownBy(() -> new AssistantFinancialInsightService(financeClient, auditService).insight(
                        SUBJECT,
                        LocalDate.parse("2025-01-01"),
                        LocalDate.parse("2026-08-18"),
                        "USD"))
                .isInstanceOf(AssistantFinanceUnavailableException.class);
    }
}
