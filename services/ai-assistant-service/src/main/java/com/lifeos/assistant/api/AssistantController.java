package com.lifeos.assistant.api;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantAccessService;
import com.lifeos.assistant.authorization.AssistantAuthenticationFailure;
import com.lifeos.assistant.authorization.AssistantIdentityDependencyUnavailable;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.conversation.AssistantConversation;
import com.lifeos.assistant.conversation.AssistantConversationService;
import com.lifeos.assistant.conversation.AssistantGenerationResult;
import com.lifeos.assistant.history.AssistantConversationHistoryStore;
import com.lifeos.assistant.tool.AssistantTaskGoalClient;
import com.lifeos.assistant.tool.AssistantToolExecutionService;
import com.lifeos.assistant.tool.AssistantToolIdempotencyKey;
import com.lifeos.assistant.tool.AssistantToolOperation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.lifeos.assistant.retrieval.GroundedQuestionService;
import com.lifeos.assistant.recommendation.AssistantRecommendationService;
import com.lifeos.assistant.finance.AssistantFinancialInsightService;
import com.lifeos.assistant.journal.AssistantJournalSummaryService;
import com.lifeos.assistant.analytics.AssistantAnalyticsRecommendationService;

/** Public authenticated API for owner-scoped assistant conversation metadata and requests. */
@RestController
@Validated
public class AssistantController {

    private final AssistantAccessService accessService;
    private final AssistantConversationService conversationService;
    private final AssistantAuditService auditService;
    private final GroundedQuestionService groundedQuestionService;
    private final AssistantToolExecutionService toolExecutionService;
    private final AssistantRecommendationService recommendationService;
    private final AssistantFinancialInsightService financialInsightService;
    private final AssistantJournalSummaryService journalSummaryService;
    private final AssistantAnalyticsRecommendationService analyticsRecommendationService;

    public AssistantController(
            AssistantAccessService accessService,
            AssistantConversationService conversationService,
            AssistantAuditService auditService,
            GroundedQuestionService groundedQuestionService,
            AssistantToolExecutionService toolExecutionService,
            AssistantRecommendationService recommendationService,
            AssistantFinancialInsightService financialInsightService,
            AssistantJournalSummaryService journalSummaryService,
            AssistantAnalyticsRecommendationService analyticsRecommendationService) {
        this.accessService = accessService;
        this.conversationService = conversationService;
        this.auditService = auditService;
        this.groundedQuestionService = groundedQuestionService;
        this.toolExecutionService = toolExecutionService;
        this.recommendationService = recommendationService;
        this.financialInsightService = financialInsightService;
        this.journalSummaryService = journalSummaryService;
        this.analyticsRecommendationService = analyticsRecommendationService;
    }

    @PostMapping("/api/v1/assistant/conversations")
    public ResponseEntity<AssistantDtos.ConversationResponse> createConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody AssistantDtos.CreateConversationRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        AssistantConversation conversation = conversationService.createConversation(subject, request.purpose());
        return ResponseEntity.created(URI.create("/api/v1/assistant/conversations/" + conversation.getId()))
                .eTag(etag(conversation.getVersion()))
                .body(conversationResponse(conversation));
    }

    @GetMapping("/api/v1/assistant/conversations/{conversationId}")
    public ResponseEntity<AssistantDtos.ConversationResponse> readConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable UUID conversationId) {
        AssistantSubject subject = authenticate(authorizationHeader);
        AssistantConversation conversation = conversationService.readConversation(subject, conversationId);
        return ResponseEntity.ok().eTag(etag(conversation.getVersion())).body(conversationResponse(conversation));
    }

    @PostMapping("/api/v1/assistant/conversations/{conversationId}/requests")
    public AssistantDtos.AssistantResponse requestResponse(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AssistantDtos.AssistantRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        AssistantGenerationResult result = conversationService.requestResponse(
                subject,
                conversationId,
                request.message(),
                request.maxOutputTokens(),
                request.toolOperation());
        return new AssistantDtos.AssistantResponse(
                result.conversationId(),
                result.purpose(),
                result.content(),
                result.estimatedInputTokens(),
                result.maxOutputTokens(),
                result.safetyFlags(),
                result.toolPlan(),
                result.providerId(),
                result.modelName(),
                result.confidenceScore(),
                false);
    }

    @GetMapping("/api/v1/assistant/conversations/{conversationId}/history")
    public List<AssistantDtos.ConversationMessageResponse> readHistory(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable UUID conversationId) {
        AssistantSubject subject = authenticate(authorizationHeader);
        return conversationService.readHistory(subject, conversationId).stream()
                .map(this::historyResponse)
                .toList();
    }

    @PostMapping("/api/v1/assistant/conversations/{conversationId}/summary")
    public AssistantDtos.ConversationSummaryResponse summarizeConversation(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable UUID conversationId,
            @Valid @RequestBody(required = false) AssistantDtos.ConversationSummaryRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        var summary = conversationService.summarizeConversation(
                subject, conversationId, request == null ? null : request.maxOutputTokens());
        return new AssistantDtos.ConversationSummaryResponse(
                summary.conversationId(),
                summary.sourceMessageCount(),
                summary.content(),
                summary.providerId(),
                summary.modelName(),
                summary.confidenceScore());
    }

    @PostMapping("/api/v1/assistant/grounded-questions")
    public AssistantDtos.GroundedAnswerResponse groundedQuestion(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody AssistantDtos.GroundedQuestionRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        var answer = groundedQuestionService.answer(
                subject,
                request.query(),
                request.maxOutputTokens(),
                request.maxSources() == null ? 8 : request.maxSources());
        return new AssistantDtos.GroundedAnswerResponse(
                answer.content(),
                answer.sourceDocumentIds(),
                answer.evidenceSufficient(),
                answer.degraded(),
                answer.providerId(),
                answer.modelName(),
                answer.confidenceScore());
    }

    @PostMapping("/api/v1/assistant/recommendations")
    public AssistantDtos.RecommendationResponse recommendations(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody(required = false) AssistantDtos.RecommendationRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        List<AssistantRecommendationService.Recommendation> recommendations = recommendationService.recommend(
                subject, request == null ? null : request.maxResults());
        return new AssistantDtos.RecommendationResponse(recommendations, recommendations.isEmpty(), "TASK_GOAL");
    }

    @PostMapping("/api/v1/assistant/financial-insights")
    public AssistantDtos.FinancialInsightResponse financialInsights(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody AssistantDtos.FinancialInsightRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        AssistantFinancialInsightService.FinancialInsight insight = financialInsightService.insight(
                subject, request.from(), request.to(), request.currency());
        return new AssistantDtos.FinancialInsightResponse(
                insight.currency(),
                insight.from(),
                insight.to(),
                insight.incomeMinor(),
                insight.expenseMinor(),
                insight.netMinor(),
                insight.categories().stream()
                        .map(category -> new AssistantDtos.FinancialInsightCategory(
                                category.category(),
                                category.incomeMinor(),
                                category.expenseMinor(),
                                category.netMinor()))
                        .toList(),
                insight.truncated(),
                insight.limitations(),
                "FINANCE");
    }

    @PostMapping("/api/v1/assistant/journal-summary")
    public AssistantDtos.JournalSummaryResponse journalSummary(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody(required = false) AssistantDtos.JournalSummaryRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        var summary = journalSummaryService.summarize(
                subject,
                request == null ? null : request.maxEntries(),
                request == null ? null : request.maxCharacters());
        return new AssistantDtos.JournalSummaryResponse(
                summary.content(), summary.sourceJournalIds(), summary.truncated(), summary.limitations(), "PROFILE_JOURNAL");
    }

    @PostMapping("/api/v1/assistant/analytics-recommendations")
    public AssistantDtos.AnalyticsRecommendationResponse analyticsRecommendations(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody(required = false) AssistantDtos.AnalyticsRecommendationRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        var recommendations = analyticsRecommendationService.recommend(
                subject, request == null ? null : request.periodDays());
        return new AssistantDtos.AnalyticsRecommendationResponse(
                recommendations.recommendations(), recommendations.truncated(), recommendations.limitations(), "ANALYTICS");
    }

    @PostMapping("/api/v1/assistant/documents/{documentId}/summary")
    public AssistantDtos.DocumentSummaryResponse summarizeDocument(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable UUID documentId,
            @Valid @RequestBody(required = false) AssistantDtos.DocumentSummaryRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        var summary = groundedQuestionService.summarize(
                subject, documentId, request == null ? null : request.maxOutputTokens());
        return new AssistantDtos.DocumentSummaryResponse(
                summary.documentId(),
                summary.documentVersion(),
                summary.sourceChunkIds(),
                summary.content(),
                true,
                summary.providerId(),
                summary.modelName(),
                summary.confidenceScore());
    }

    @PostMapping("/api/v1/assistant/conversations/{conversationId}/tool-executions")
    public ResponseEntity<AssistantDtos.ToolExecutionResponse> executeTool(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @PathVariable UUID conversationId,
            @RequestHeader(value = AssistantToolIdempotencyKey.HEADER_NAME, required = false)
                    List<String> idempotencyKeys,
            @Valid @RequestBody AssistantDtos.ToolExecutionRequest request) {
        AssistantSubject subject = authenticate(authorizationHeader);
        conversationService.readConversation(subject, conversationId);
        String idempotencyKey = AssistantToolIdempotencyKey.requireSingle(idempotencyKeys);
        AssistantTaskGoalClient.TaskCreationResult result = toolExecutionService.execute(
                subject,
                conversationId,
                request.operation(),
                request.title(),
                request.priority(),
                request.dueAt(),
                idempotencyKey,
                request.confirmed());
        AssistantDtos.ToolExecutionResponse response = new AssistantDtos.ToolExecutionResponse(
                result.id(),
                result.title(),
                result.status(),
                result.version(),
                result.createdAt(),
                result.updatedAt(),
                result.completedAt(),
                result.canceledAt(),
                result.priority(),
                result.dueAt());
        ResponseEntity.BodyBuilder responseBuilder = request.operation() == AssistantToolOperation.DRAFT_FINANCIAL_NOTE
                ? ResponseEntity.accepted()
                : ResponseEntity.status(201)
                        .header(
                                HttpHeaders.LOCATION,
                                (request.operation() == AssistantToolOperation.DRAFT_GOAL
                                                ? "/api/v1/goals/"
                                                : "/api/v1/tasks/")
                                        + result.id())
                        .eTag("\"" + result.version() + "\"");
        return responseBuilder.header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    private AssistantSubject authenticate(String authorizationHeader) {
        try {
            return accessService.authenticate(authorizationHeader);
        } catch (AssistantAuthenticationFailure exception) {
            auditService.recordAuthenticationFailure(AssistantAuditOutcome.AUTHENTICATION_FAILED);
            throw exception;
        } catch (AssistantIdentityDependencyUnavailable exception) {
            auditService.recordAuthenticationFailure(AssistantAuditOutcome.IDENTITY_UNAVAILABLE);
            throw exception;
        }
    }

    private static AssistantDtos.ConversationResponse conversationResponse(AssistantConversation conversation) {
        return new AssistantDtos.ConversationResponse(
                conversation.getId(),
                conversation.getPurpose(),
                conversation.getStatus(),
                conversation.getVersion(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                false);
    }

    private AssistantDtos.ConversationMessageResponse historyResponse(
            AssistantConversationHistoryStore.HistoryEntry entry) {
        return new AssistantDtos.ConversationMessageResponse(entry.role(), entry.content(), entry.createdAt());
    }

    private static String etag(long version) {
        return '"' + Long.toString(version) + '"';
    }
}
