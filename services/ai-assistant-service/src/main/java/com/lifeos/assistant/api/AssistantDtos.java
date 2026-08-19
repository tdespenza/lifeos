package com.lifeos.assistant.api;

import com.lifeos.assistant.conversation.AssistantConversationPurpose;
import com.lifeos.assistant.conversation.AssistantConversationStatus;
import com.lifeos.assistant.tool.AssistantToolOperation;
import com.lifeos.assistant.tool.AssistantToolPlan;
import com.lifeos.assistant.recommendation.AssistantRecommendationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Stable, bounded public DTOs for the assistant's metadata-only conversation API. */
public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record CreateConversationRequest(@NotNull AssistantConversationPurpose purpose) {
    }

    public record ConversationResponse(
            UUID id,
            AssistantConversationPurpose purpose,
            AssistantConversationStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            boolean retainsPromptOrOutput) {
    }

    public record ConversationMessageResponse(String role, String content, Instant createdAt) {

        @Override
        public String toString() {
            return "ConversationMessageResponse[role=" + role + ", content=[redacted]]";
        }
    }

    public record ConversationSummaryRequest(@Max(2_048) Integer maxOutputTokens) {
    }

    public record ConversationSummaryResponse(
            UUID conversationId,
            int sourceMessageCount,
            String content,
            String providerId,
            String modelName,
            BigDecimal confidenceScore) {

        @Override
        public String toString() {
            return "ConversationSummaryResponse[conversationId=" + conversationId + ", content=[redacted]]";
        }
    }

    public record AssistantRequest(
            @NotBlank @Size(max = 16_384) String message,
            @Max(2_048) Integer maxOutputTokens,
            @Size(max = 64) String toolOperation) {

        @Override
        public String toString() {
            return "AssistantRequest[redacted]";
        }
    }

    public record GroundedQuestionRequest(
            @NotBlank @Size(max = 16_384) String query,
            @Max(2_048) Integer maxOutputTokens,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(32) Integer maxSources) {

        @Override
        public String toString() {
            return "GroundedQuestionRequest[redacted]";
        }
    }

    public record GroundedAnswerResponse(
            String content,
            List<UUID> sourceDocumentIds,
            boolean evidenceSufficient,
            boolean degraded,
            String providerId,
            String modelName,
            BigDecimal confidenceScore) {

        @Override
        public String toString() {
            return "GroundedAnswerResponse[redacted]";
        }
    }

    public record DocumentSummaryRequest(@Max(2_048) Integer maxOutputTokens) {
    }

    public record DocumentSummaryResponse(
            UUID documentId,
            long documentVersion,
            List<UUID> sourceChunkIds,
            String content,
            boolean generated,
            String providerId,
            String modelName,
            BigDecimal confidenceScore) {

        @Override
        public String toString() {
            return "DocumentSummaryResponse[documentId=" + documentId + ", content=[redacted]]";
        }
    }

    public record AssistantResponse(
            UUID conversationId,
            AssistantConversationPurpose purpose,
            String content,
            int estimatedInputTokens,
            int maxOutputTokens,
            List<String> safetyFlags,
            AssistantToolPlan toolPlan,
            String providerId,
            String modelName,
            BigDecimal confidenceScore,
            boolean contentRetained) {

        @Override
        public String toString() {
            return "AssistantResponse[conversationId=" + conversationId + ", purpose=" + purpose
                    + ", content=[redacted]]";
        }
    }

    public record ErrorResponse(String code, String message, boolean retryable, String correlationId) {
    }

    public record ToolOperationResponse(
            AssistantToolOperation operation, String executionState, boolean requiresUserConfirmation, String reason) {
    }

    public record ToolExecutionRequest(
            @NotNull AssistantToolOperation operation,
            @NotBlank @Size(max = 255) String title,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(4) Integer priority,
            Instant dueAt,
            boolean confirmed) {
    }

    public record ToolExecutionResponse(
            UUID taskId,
            String title,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Instant canceledAt,
            int priority,
            Instant dueAt) {
    }

    public record RecommendationRequest(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(8) Integer maxResults) {
    }

    public record RecommendationResponse(
            List<AssistantRecommendationService.Recommendation> recommendations,
            boolean degraded,
            String source) {
    }

    public record FinancialInsightRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    public record FinancialInsightResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            long incomeMinor,
            long expenseMinor,
            long netMinor,
            List<FinancialInsightCategory> categories,
            boolean truncated,
            List<String> limitations,
            String source) {
    }

    public record FinancialInsightCategory(
            String category, long incomeMinor, long expenseMinor, long netMinor) {
    }

    public record JournalSummaryRequest(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10) Integer maxEntries,
            @jakarta.validation.constraints.Min(256) @jakarta.validation.constraints.Max(16_384) Integer maxCharacters) {
    }

    public record JournalSummaryResponse(
            String content, List<UUID> sourceJournalIds, boolean truncated, List<String> limitations, String source) {

        @Override
        public String toString() {
            return "JournalSummaryResponse[source=" + source + ", content=[redacted]]";
        }
    }

    public record AnalyticsRecommendationRequest(
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(90) Integer periodDays) {
    }

    public record AnalyticsRecommendationResponse(
            List<com.lifeos.assistant.analytics.AssistantAnalyticsRecommendationService.Recommendation> recommendations,
            boolean truncated,
            List<String> limitations,
            String source) {
    }
}
