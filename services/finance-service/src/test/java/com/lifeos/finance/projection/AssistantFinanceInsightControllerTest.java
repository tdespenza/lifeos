package com.lifeos.finance.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.finance.api.FinanceDtos;
import com.lifeos.finance.config.FinanceAssistantProjectionProperties;
import com.lifeos.finance.service.FinanceManagementService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AssistantFinanceInsightControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);

    @Mock
    private FinanceManagementService financeService;

    private AssistantFinanceInsightController controller;

    @BeforeEach
    void setUp() {
        FinanceAssistantProjectionProperties properties = new FinanceAssistantProjectionProperties();
        properties.setWorkloadIdentity("ai-assistant-service");
        properties.setWorkloadToken("assistant-secret");
        controller = new AssistantFinanceInsightController(financeService, properties);
    }

    @Test
    void returnsAggregateOnlyFactsAfterWorkloadValidation() {
        when(financeService.insights(any(), any())).thenReturn(new FinanceDtos.InsightsResponse(
                "USD",
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-18"),
                100_00,
                40_00,
                60_00,
                List.of(new FinanceDtos.CategorySpendResponse("food", 40_00, 0, -40_00)),
                0,
                32,
                false,
                false,
                List.of("NO_FX_CONVERSION")));

        var response = controller.insights(
                "ai-assistant-service",
                "assistant-secret",
                new AssistantFinanceInsightController.FinanceInsightsProjectionRequest(
                        ACCOUNT_ID,
                        SESSION_ID,
                        "password",
                        PROOF,
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-18"),
                        "USD"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().netMinor()).isEqualTo(60_00);
        assertThat(response.getBody().categories()).hasSize(1);
        verify(financeService).insights(any(), any());
    }

    @Test
    void rejectsMismatchedWorkloadBeforeFinanceAccess() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.insights(
                        "wrong-service",
                        "assistant-secret",
                        new AssistantFinanceInsightController.FinanceInsightsProjectionRequest(
                                ACCOUNT_ID,
                                SESSION_ID,
                                "password",
                                PROOF,
                                LocalDate.parse("2026-08-01"),
                                LocalDate.parse("2026-08-18"),
                                "USD")))
                .isInstanceOf(AssistantFinanceInsightController.AssistantFinanceWorkloadUnauthorizedException.class);
    }
}
