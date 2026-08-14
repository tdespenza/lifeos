package com.lifeos.identity.account;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository repository;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalManagementPort
    private int managementPort;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void registerReturnsCreatedAccountAndServerGeneratedCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("X-Correlation-ID", "registration-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","displayName":"Ada Lovelace"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/")))
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"));
    }

    @Test
    void registerRejectsMalformedInput() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","displayName":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("not-an-email"))));
    }

    @Test
    void replacesUnsafeCorrelationIdWithGeneratedValue() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/00000000-0000-0000-0000-000000000000")
                        .header("X-Correlation-ID", "contains spaces"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void preservesAValidatedCorrelationIdFromTheGateway() throws Exception {
        String correlationId = "11111111-1111-4111-8111-111111111111";

        mockMvc.perform(get("/api/v1/accounts/00000000-0000-0000-0000-000000000000")
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", correlationId));
    }

    @Test
    void registerRejectsDuplicateEmailWithoutEchoingPersonalData() throws Exception {
        String request = """
                {"email":"ada@example.com","displayName":"Ada Lovelace"}
                """;

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("ada@example.com"))));
    }

    @Test
    void exposesHealthReadinessLivenessAndPrometheusEndpoints() throws Exception {
        ResponseEntity<String> health = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertTrue(health.getBody().contains("\"status\":\"UP\""));

        ResponseEntity<String> liveness = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health/liveness", String.class);
        assertEquals(HttpStatus.OK, liveness.getStatusCode());

        ResponseEntity<String> readiness = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, readiness.getStatusCode());

        ResponseEntity<String> prometheus = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, prometheus.getStatusCode());
        assertTrue(prometheus.getBody().contains("application_ready_time_seconds"));
    }
}
