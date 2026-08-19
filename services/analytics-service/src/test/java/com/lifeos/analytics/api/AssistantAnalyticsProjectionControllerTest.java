package com.lifeos.analytics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.analytics.config.AnalyticsProperties;
import com.lifeos.analytics.config.GatewayProofVerifier;
import com.lifeos.analytics.projection.AnalyticsProjectionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssistantAnalyticsProjectionControllerTest {
    @Test
    void rejectsWrongWorkload() {
        AnalyticsProperties properties = properties();
        AssistantAnalyticsProjectionController controller = new AssistantAnalyticsProjectionController(
                mock(AnalyticsProjectionService.class), mock(GatewayProofVerifier.class), properties);
        assertThatThrownBy(() -> controller.insights("wrong", "wrong", "proof", request()))
                .isInstanceOf(AssistantAnalyticsProjectionController.AssistantAnalyticsUnauthorizedException.class);
    }

    @Test
    void returnsBoundedInsightsAfterProofValidation() {
        AnalyticsProjectionService projections = mock(AnalyticsProjectionService.class);
        GatewayProofVerifier verifier = mock(GatewayProofVerifier.class);
        AnalyticsProperties properties = properties();
        UUID account = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        when(verifier.isValid("POST", AssistantAnalyticsProjectionController.PATH,
                account.toString(), session.toString(), "proof")).thenReturn(true);
        when(projections.productivityInsights(account, "personal:" + account, 30)).thenReturn(List.of(
                new AnalyticsProjectionService.ProductivityInsight(
                        "focus-time", 42, List.of("focus.minutes"), "analytics-v1")));
        AssistantAnalyticsProjectionController.AnalyticsInsightsProjectionResponse response =
                new AssistantAnalyticsProjectionController(projections, verifier, properties)
                        .insights("ai-assistant-service", "secret", "proof", request(account, session)).getBody();
        assertThat(response.insights()).singleElement().extracting(AnalyticsProjectionService.ProductivityInsight::key)
                .isEqualTo("focus-time");
    }

    private static AnalyticsProperties properties() {
        AnalyticsProperties properties = new AnalyticsProperties();
        properties.setAssistantWorkloadToken("secret");
        return properties;
    }

    private static AssistantAnalyticsProjectionController.AnalyticsInsightsProjectionRequest request() {
        return request(UUID.randomUUID(), UUID.randomUUID());
    }

    private static AssistantAnalyticsProjectionController.AnalyticsInsightsProjectionRequest request(UUID account, UUID session) {
        return new AssistantAnalyticsProjectionController.AnalyticsInsightsProjectionRequest(
                account, session, "password", "a".repeat(64), 30);
    }
}
