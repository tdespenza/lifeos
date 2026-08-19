package com.lifeos.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.analytics.config.GatewayProofVerifier;
import com.lifeos.analytics.projection.AnalyticsProjectionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsDashboardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalyticsProjectionService projections;

    @Test
    void authenticatedGatewayProofReturnsOnlyOwnDashboard() throws Exception {
        UUID account = UUID.randomUUID();
        projections.record(account, "personal:" + account, "notifications.requested", 4, 30);
        String path = "/api/v1/analytics/dashboard";
        String session = UUID.randomUUID().toString();
        String proof = proof("GET", path, account.toString(), session, "test-gateway-proof-secret");

        String body = mockMvc.perform(get(path)
                        .param("periodDays", "30")
                        .header(GatewayProofVerifier.ACCOUNT_HEADER, account)
                        .header(GatewayProofVerifier.SESSION_HEADER, session)
                        .header(GatewayProofVerifier.PROOF_HEADER, proof))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("notifications.requested").contains("\"value\":4");
    }

    @Test
    void missingGatewayProofIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/dashboard")
                        .header(GatewayProofVerifier.ACCOUNT_HEADER, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedGatewayProofReturnsDeterministicProductivityInsights() throws Exception {
        UUID account = UUID.randomUUID();
        projections.record(account, "personal:" + account, "tasks.created", 10, 30);
        projections.record(account, "personal:" + account, "tasks.completed", 8, 30);
        String path = "/api/v1/analytics/insights";
        String session = UUID.randomUUID().toString();
        String proof = proof("GET", path, account.toString(), session, "test-gateway-proof-secret");

        String body = mockMvc.perform(get(path)
                        .param("periodDays", "30")
                        .header(GatewayProofVerifier.ACCOUNT_HEADER, account)
                        .header(GatewayProofVerifier.SESSION_HEADER, session)
                        .header(GatewayProofVerifier.PROOF_HEADER, proof))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("task-follow-through").contains("\"score\":80");
    }

    @Test
    void authenticatedGatewayProofReturnsBoundedMetricTrend() throws Exception {
        UUID account = UUID.randomUUID();
        projections.record(account, "personal:" + account, "habits.completed", 6, 30);
        String path = "/api/v1/analytics/trends";
        String session = UUID.randomUUID().toString();
        String proof = proof("GET", path, account.toString(), session, "test-gateway-proof-secret");

        String body = mockMvc.perform(get(path)
                        .param("metricKey", "habits.completed")
                        .param("periodDays", "30")
                        .param("days", "30")
                        .header(GatewayProofVerifier.ACCOUNT_HEADER, account)
                        .header(GatewayProofVerifier.SESSION_HEADER, session)
                        .header(GatewayProofVerifier.PROOF_HEADER, proof))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("habits.completed").contains("\"value\":6");
    }

    private static String proof(String method, String path, String account, String session, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((method + "\n" + path + "\n" + account + "\n" + session)
                .getBytes(StandardCharsets.UTF_8)));
    }
}
