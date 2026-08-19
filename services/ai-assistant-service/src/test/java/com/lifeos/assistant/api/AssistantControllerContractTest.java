package com.lifeos.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.audit.AssistantRequestAuditEventRepository;
import com.lifeos.assistant.authorization.AssistantAccessService;
import com.lifeos.assistant.authorization.AssistantAuthenticationFailure;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.conversation.AssistantConversationRepository;
import com.lifeos.assistant.recommendation.AssistantRecommendationService;
import com.lifeos.assistant.finance.AssistantFinanceClient;
import com.lifeos.assistant.finance.AssistantFinancialInsightService;
import com.lifeos.assistant.journal.AssistantJournalSummaryService;
import com.lifeos.assistant.analytics.AssistantAnalyticsRecommendationService;
import com.lifeos.assistant.tool.AssistantTaskGoalClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Executable HTTP contract for owner scope, safety, and the disabled-provider response. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-service-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=assistant-contract-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "ai-assistant.audit-hmac-secret=assistant-contract-audit-secret",
    "identity.workload-token=assistant-contract-workload-token"
})
@AutoConfigureMockMvc
class AssistantControllerContractTest {

    private static final String BEARER = "Bearer assistant-contract-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssistantConversationRepository conversationRepository;

    @Autowired
    private AssistantRequestAuditEventRepository auditRepository;

    @MockitoBean
    private AssistantAccessService accessService;

    @MockitoBean
    private AssistantTaskGoalClient taskGoalClient;

    @MockitoBean
    private AssistantRecommendationService recommendationService;

    @MockitoBean
    private AssistantFinancialInsightService financialInsightService;

    @MockitoBean
    private AssistantJournalSummaryService journalSummaryService;

    @MockitoBean
    private AssistantAnalyticsRecommendationService analyticsRecommendationService;

    private AssistantSubject subject;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        conversationRepository.deleteAll();
        reset(accessService);
        reset(taskGoalClient);
        reset(recommendationService);
        reset(financialInsightService);
        reset(journalSummaryService);
        reset(analyticsRecommendationService);
        subject = subject();
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void createsOwnerScopedMetadataWithoutRetainingPromptOrOutput() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/assistant/conversations")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"GOAL_PLANNING\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/assistant/conversations/")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.purpose").value("GOAL_PLANNING"))
                .andExpect(jsonPath("$.retainsPromptOrOutput").value(false))
                .andReturn();

        UUID conversationId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
        mockMvc.perform(get("/api/v1/assistant/conversations/{conversationId}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()))
                .andExpect(jsonPath("$.retainsPromptOrOutput").value(false));
    }

    @Test
    void crossOwnerAndMissingConversationReturnTheSameNoDisclosureResponse() throws Exception {
        UUID conversationId = createConversation();
        when(accessService.authenticate(anyString())).thenReturn(otherSubject());

        MvcResult crossOwner = mockMvc.perform(get("/api/v1/assistant/conversations/{conversationId}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/assistant/conversations/{conversationId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode crossOwnerBody = objectMapper.readTree(crossOwner.getResponse().getContentAsString());
        JsonNode missingBody = objectMapper.readTree(missing.getResponse().getContentAsString());
        assertThat(crossOwnerBody.path("code").asText()).isEqualTo(missingBody.path("code").asText());
        assertThat(crossOwnerBody.path("message").asText()).isEqualTo(missingBody.path("message").asText());
        assertThat(crossOwnerBody.path("retryable").asBoolean()).isEqualTo(missingBody.path("retryable").asBoolean());
        assertThat(crossOwner.getResponse().getContentAsString()).doesNotContain(conversationId.toString());
    }

    @Test
    void disabledProviderFailsClosedWithAStableNonContentError() throws Exception {
        UUID conversationId = createConversation();
        String prompt = "Please plan around alex@example.test";

        MvcResult result = mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/requests", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + prompt + "\",\"maxOutputTokens\":32,\"toolOperation\":\"DRAFT_TASK\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(prompt, "alex@example.test");
    }

    @Test
    void groundedQuestionFailsClosedWhenVectorSearchIsNotProvisioned() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/assistant/grounded-questions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"What does my document say?\",\"maxSources\":4}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GROUNDED_ANSWER_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("What does my document say?");
    }

    @Test
    void documentSummaryFailsClosedWithoutIndexedSourceOrProvider() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/documents/{documentId}/summary", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GROUNDED_ANSWER_UNAVAILABLE"));
    }

    @Test
    void historyReadFailsClosedWhenEncryptedMongoRetentionIsDisabled() throws Exception {
        UUID conversationId = createConversation();

        mockMvc.perform(get("/api/v1/assistant/conversations/{conversationId}/history", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_HISTORY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void conversationSummaryFailsClosedWhenEncryptedMongoRetentionIsDisabled() throws Exception {
        UUID conversationId = createConversation();

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/summary", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_HISTORY_UNAVAILABLE"));
    }

    @Test
    void rejectsUnknownToolOperationsRatherThanDispatchingAnythingArbitrary() throws Exception {
        UUID conversationId = createConversation();

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/requests", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Help me plan\",\"toolOperation\":\"RUN_SHELL\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TOOL_OPERATION_NOT_ALLOWED"));
    }

    @Test
    void requiresExplicitConfirmationBeforeExecutingTaskTool() throws Exception {
        UUID conversationId = createConversation();

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/tool-executions", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "assistant-task-confirmation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DRAFT_TASK\",\"title\":\"Call dentist\",\"confirmed\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOOL_CONFIRMATION_REQUIRED"));

        verify(taskGoalClient, never()).createTask(any(), anyString(), any(), any(), anyString());
    }

    @Test
    void executesConfirmedTaskThroughTheBoundedTaskGoalAdapter() throws Exception {
        UUID conversationId = createConversation();
        UUID taskId = UUID.randomUUID();
        java.time.Instant createdAt = java.time.Instant.parse("2026-08-18T12:00:00Z");
        when(taskGoalClient.createTask(any(), anyString(), any(), any(), anyString()))
                .thenReturn(new AssistantTaskGoalClient.TaskCreationResult(
                        taskId, "Call dentist", "ACTIVE", 0, createdAt, createdAt, null, null, 3, null));

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/tool-executions", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "assistant-task-confirmation-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DRAFT_TASK\",\"title\":\"Call dentist\",\"confirmed\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/tasks/" + taskId))
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(taskGoalClient).createTask(
                org.mockito.ArgumentMatchers.eq(subject),
                org.mockito.ArgumentMatchers.eq("Call dentist"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("assistant-task-confirmation-2"));
    }

    @Test
    void executesConfirmedGoalThroughTheBoundedTaskGoalAdapter() throws Exception {
        UUID conversationId = createConversation();
        UUID goalId = UUID.randomUUID();
        java.time.Instant createdAt = java.time.Instant.parse("2026-08-18T12:00:00Z");
        when(taskGoalClient.createGoal(any(), anyString(), any(), any(), anyString()))
                .thenReturn(new AssistantTaskGoalClient.TaskCreationResult(
                        goalId, "Finish roadmap", "ACTIVE", 0, createdAt, createdAt, null, null, 1, null));

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/tool-executions", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "assistant-goal-confirmation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DRAFT_GOAL\",\"title\":\"Finish roadmap\",\"confirmed\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/goals/" + goalId))
                .andExpect(jsonPath("$.taskId").value(goalId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(taskGoalClient).createGoal(
                org.mockito.ArgumentMatchers.eq(subject),
                org.mockito.ArgumentMatchers.eq("Finish roadmap"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("assistant-goal-confirmation-1"));
    }

    @Test
    void rejectsConfirmedToolKeyReuseWithDifferentPayload() throws Exception {
        UUID conversationId = createConversation();
        UUID taskId = UUID.randomUUID();
        java.time.Instant createdAt = java.time.Instant.parse("2026-08-18T12:00:00Z");
        when(taskGoalClient.createTask(any(), anyString(), any(), any(), anyString()))
                .thenReturn(new AssistantTaskGoalClient.TaskCreationResult(
                        taskId, "Call dentist", "ACTIVE", 0, createdAt, createdAt, null, null, 3, null));

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/tool-executions", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "assistant-confirmation-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DRAFT_TASK\",\"title\":\"Call dentist\",\"confirmed\":true}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/tool-executions", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "assistant-confirmation-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DRAFT_GOAL\",\"title\":\"Book vacation\",\"confirmed\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOOL_CONFIRMATION_CONFLICT"));

        verify(taskGoalClient).createTask(any(), anyString(), any(), any(), anyString());
        verify(taskGoalClient, never()).createGoal(any(), anyString(), any(), any(), anyString());
    }

    @Test
    void returnsOwnerScopedGoalPlanningRecommendations() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(recommendationService.recommend(org.mockito.ArgumentMatchers.eq(subject), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(List.of(new AssistantRecommendationService.Recommendation(
                        "TASK",
                        taskId,
                        "Call dentist",
                        "Due within two days",
                        1,
                        java.time.Instant.parse("2026-08-19T15:00:00Z"))));

        mockMvc.perform(post("/api/v1/assistant/recommendations")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxResults\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.source").value("TASK_GOAL"))
                .andExpect(jsonPath("$.recommendations[0].resourceId").value(taskId.toString()))
                .andExpect(jsonPath("$.recommendations[0].title").value("Call dentist"));
    }

    @Test
    void returnsAggregateFinancialInsightsWithoutRawTransactions() throws Exception {
        when(financialInsightService.insight(
                org.mockito.ArgumentMatchers.eq(subject),
                org.mockito.ArgumentMatchers.eq(java.time.LocalDate.parse("2026-08-01")),
                org.mockito.ArgumentMatchers.eq(java.time.LocalDate.parse("2026-08-18")),
                org.mockito.ArgumentMatchers.eq("USD")))
                .thenReturn(new AssistantFinancialInsightService.FinancialInsight(
                        "USD",
                        java.time.LocalDate.parse("2026-08-01"),
                        java.time.LocalDate.parse("2026-08-18"),
                        10000,
                        4000,
                        6000,
                        List.of(new AssistantFinanceClient.Category("food", 0, 4000, -4000)),
                        false,
                        List.of("NO_FX_CONVERSION")));

        mockMvc.perform(post("/api/v1/assistant/financial-insights")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\",\"from\":\"2026-08-01\",\"to\":\"2026-08-18\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FINANCE"))
                .andExpect(jsonPath("$.netMinor").value(6000))
                .andExpect(jsonPath("$.categories[0].category").value("food"));
    }

    @Test
    void returnsDeterministicJournalSummaryWithSourceIds() throws Exception {
        UUID journalId = UUID.randomUUID();
        when(journalSummaryService.summarize(
                org.mockito.ArgumentMatchers.eq(subject), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AssistantJournalSummaryService.JournalSummary(
                        "Renewal: date is Friday.", List.of(journalId), false, List.of()));

        mockMvc.perform(post("/api/v1/assistant/journal-summary")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROFILE_JOURNAL"))
                .andExpect(jsonPath("$.sourceJournalIds[0]").value(journalId.toString()))
                .andExpect(jsonPath("$.content").value("Renewal: date is Friday."));
    }

    @Test
    void returnsAnalyticsRecommendationsWithoutMutation() throws Exception {
        when(analyticsRecommendationService.recommend(
                org.mockito.ArgumentMatchers.eq(subject), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AssistantAnalyticsRecommendationService.AnalyticsRecommendations(
                        List.of(new AssistantAnalyticsRecommendationService.Recommendation(
                                "focus-time", "Protect another focused block.", 42, List.of("focus.minutes"), 30)),
                        false,
                        List.of()));

        mockMvc.perform(post("/api/v1/assistant/analytics-recommendations")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ANALYTICS"))
                .andExpect(jsonPath("$.recommendations[0].key").value("focus-time"))
                .andExpect(jsonPath("$.recommendations[0].score").value(42))
                .andExpect(jsonPath("$.recommendations[0].periodDays").value(30));
    }

    @Test
    void returnsConfirmedFinancialNoteAsNonMutatingProposal() throws Exception {
        UUID conversationId = createConversation();

        mockMvc.perform(post("/api/v1/assistant/conversations/{conversationId}/tool-executions", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "financial-note-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"DRAFT_FINANCIAL_NOTE\",\"title\":\"Review dining spend\","
                                + "\"confirmed\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").doesNotExist())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.title").value("Review dining spend"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void absentBearerFailsClosedBeforeConversationWork() throws Exception {
        doThrow(new AssistantAuthenticationFailure()).when(accessService).authenticate(isNull());

        mockMvc.perform(post("/api/v1/assistant/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"GENERAL\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void oversizedInboundRequestsPreserveCorrelationAndUseTheStableErrorEnvelope() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String oversizedBody = "{\"purpose\":\"" + "x".repeat(16_384) + "\"}";

        mockMvc.perform(post("/api/v1/assistant/conversations")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Correlation-ID", correlationId))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("Request payload too large"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.correlationId").value(correlationId));
    }

    private UUID createConversation() throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/assistant/conversations")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"GENERAL\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return UUID.fromString(body.path("id").asText());
    }

    private static AssistantSubject subject() {
        return new AssistantSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    private static AssistantSubject otherSubject() {
        return new AssistantSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }
}
