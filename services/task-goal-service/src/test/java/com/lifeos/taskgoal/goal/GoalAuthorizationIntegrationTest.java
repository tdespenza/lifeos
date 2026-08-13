package com.lifeos.taskgoal.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDependencyUnavailable;
import com.lifeos.taskgoal.authorization.TaskSubject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:goal-authorization;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    // H2 has no CREATE INDEX CONCURRENTLY; use the test-only equivalent of the production V3.
    "spring.flyway.locations=classpath:db/migration-h2",
    "identity.workload-token=integration-test-workload-token"
})
@AutoConfigureMockMvc
class GoalAuthorizationIntegrationTest {

    private static final String BEARER = "Bearer integration-test-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GoalRepository repository;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        reset(accessService);
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void authenticatedCallerCanCreateListAndReadOnlyItsOwnGoals() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Prepare launch"))
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID ownedGoalId = UUID.fromString(createdBody.path("id").asText());

        Goal persisted = repository.findById(ownedGoalId).orElseThrow();
        assertThat(persisted.getOwnerAccountId()).isEqualTo(subject.accountId());
        assertThat(persisted.getTenantId()).isEqualTo(subject.tenantId());
        repository.save(new Goal(
                UUID.randomUUID(), "Another account's goal", UUID.randomUUID(), UUID.randomUUID().toString()));

        mockMvc.perform(get("/api/v1/goals").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ownedGoalId.toString()));
        mockMvc.perform(get("/api/v1/goals/{goalId}", ownedGoalId).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownedGoalId.toString()));

        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.CREATE), any());
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.LIST), any());
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());
    }

    @Test
    void missingAndCrossUserGoalsHaveIdenticalGenericDenyResponses() throws Exception {
        Goal crossUserGoal = repository.save(new Goal(
                UUID.randomUUID(), "Confidential goal", UUID.randomUUID(), UUID.randomUUID().toString()));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());

        MvcResult crossUser = mockMvc.perform(get("/api/v1/goals/{goalId}", crossUserGoal.getId())
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"))
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/goals/{goalId}", UUID.randomUUID())
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"))
                .andReturn();

        assertThat(crossUser.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .doesNotContain(crossUserGoal.getId().toString())
                .doesNotContain(crossUserGoal.getTitle());
        verify(accessService, org.mockito.Mockito.times(2))
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());
    }

    @Test
    void policyDenyAndDependencyFailureUseSafe403And503Responses() throws Exception {
        Goal ownedGoal = repository.save(new Goal(
                UUID.randomUUID(), "Owned goal", subject.accountId(), subject.tenantId()));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());

        mockMvc.perform(get("/api/v1/goals/{goalId}", ownedGoal.getId()).header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"));

        reset(accessService);
        when(accessService.authenticate(anyString())).thenReturn(subject);
        doThrow(new TaskAuthorizationDependencyUnavailable())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.LIST), any());

        mockMvc.perform(get("/api/v1/goals").header("Authorization", BEARER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("Authorization temporarily unavailable"));
    }
}
