package com.lifeos.assistant.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantFinanceToolProperties;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientAssistantFinanceClientTest {

    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));

    @Test
    void forwardsOnlyTheSubjectProofAndReturnsAggregates() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://finance.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientAssistantFinanceClient client = new RestClientAssistantFinanceClient(
                builder.build(), properties(), new Semaphore(1));
        server.expect(requestTo("http://finance.test/api/v1/internal/assistant/finance-insights"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-LifeOS-Workload-Identity", "ai-assistant-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "assistant-secret"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.accessTokenProof").value("a".repeat(64)))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"currency\":\"USD\",\"from\":\"2026-08-01\",\"to\":\"2026-08-18\","
                                + "\"incomeMinor\":10000,\"expenseMinor\":4000,\"netMinor\":6000,"
                                + "\"categories\":[],\"truncated\":false,\"limitations\":[\"NO_FX_CONVERSION\"]}"));

        AssistantFinanceClient.FinancialInsightSnapshot result = client.insights(
                SUBJECT,
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-18"),
                "USD");

        assertThat(result.netMinor()).isEqualTo(6000);
        server.verify();
    }

    private static AssistantFinanceToolProperties properties() {
        AssistantFinanceToolProperties properties = new AssistantFinanceToolProperties();
        properties.setBaseUrl("http://finance.test");
        properties.setWorkloadIdentity("ai-assistant-service");
        properties.setWorkloadToken("assistant-secret");
        return properties;
    }
}
