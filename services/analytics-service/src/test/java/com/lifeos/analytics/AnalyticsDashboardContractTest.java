package com.lifeos.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.analytics.config.GatewayProofVerifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsDashboardContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardUsesStableJsonContractAndBoundedPeriod() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/dashboard")
                        .param("periodDays", "91")
                        .header(GatewayProofVerifier.ACCOUNT_HEADER, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
