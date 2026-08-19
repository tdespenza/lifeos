package com.lifeos.assistant.api;

import com.lifeos.assistant.audit.AssistantAuditUnavailableException;
import com.lifeos.assistant.authorization.AssistantAuthenticationFailure;
import com.lifeos.assistant.authorization.AssistantIdentityDependencyUnavailable;
import com.lifeos.assistant.config.AssistantPayloadTooLargeException;
import com.lifeos.assistant.conversation.AssistantConversationNotFoundException;
import com.lifeos.assistant.conversation.AssistantInputLimitExceededException;
import com.lifeos.assistant.conversation.AssistantOutputLimitExceededException;
import com.lifeos.assistant.conversation.AssistantPromptRejectedException;
import com.lifeos.assistant.history.ConversationHistoryUnavailableException;
import com.lifeos.assistant.observability.CorrelationIdSupport;
import com.lifeos.assistant.provider.AssistantProviderBusyException;
import com.lifeos.assistant.provider.AssistantProviderFailureException;
import com.lifeos.assistant.provider.AssistantProviderNotConfiguredException;
import com.lifeos.assistant.provider.AssistantProviderTimeoutException;
import com.lifeos.assistant.retrieval.GroundedAnswerUnavailableException;
import com.lifeos.assistant.retrieval.GroundedDocumentDeniedException;
import com.lifeos.assistant.recommendation.AssistantRecommendationDeniedException;
import com.lifeos.assistant.recommendation.AssistantRecommendationUnavailableException;
import com.lifeos.assistant.finance.AssistantFinanceDeniedException;
import com.lifeos.assistant.finance.AssistantFinanceUnavailableException;
import com.lifeos.assistant.journal.AssistantJournalDeniedException;
import com.lifeos.assistant.journal.AssistantJournalUnavailableException;
import com.lifeos.assistant.analytics.AssistantAnalyticsDeniedException;
import com.lifeos.assistant.analytics.AssistantAnalyticsUnavailableException;
import com.lifeos.assistant.tool.AssistantToolOperationNotAllowedException;
import com.lifeos.assistant.tool.AssistantTaskToolDeniedException;
import com.lifeos.assistant.tool.AssistantTaskToolUnavailableException;
import com.lifeos.assistant.tool.AssistantToolConfirmationRequiredException;
import com.lifeos.assistant.tool.AssistantToolConfirmationConflictException;
import com.lifeos.assistant.tool.AssistantToolConfirmationUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable error envelope that never returns prompt text, output text, tokens, or provider exceptions. */
@RestControllerAdvice
public class AssistantExceptionHandler {

    @ExceptionHandler(AssistantAuthenticationFailure.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> authenticationFailure(
            AssistantAuthenticationFailure exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(error("AUTHENTICATION_REQUIRED", "A valid bearer token is required", false, request));
    }

    @ExceptionHandler(AssistantIdentityDependencyUnavailable.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> identityUnavailable(
            AssistantIdentityDependencyUnavailable exception, HttpServletRequest request) {
        return unavailable("IDENTITY_UNAVAILABLE", "Authentication is temporarily unavailable", request);
    }

    @ExceptionHandler(AssistantConversationNotFoundException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> conversationNotFound(
            AssistantConversationNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("CONVERSATION_NOT_FOUND", "Conversation not found", false, request));
    }

    @ExceptionHandler(AssistantPromptRejectedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> promptRejected(
            AssistantPromptRejectedException exception, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(error("PROMPT_REJECTED", "The request cannot be processed safely", false, request));
    }

    @ExceptionHandler(AssistantInputLimitExceededException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> inputLimitExceeded(
            AssistantInputLimitExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("INPUT_LIMIT_EXCEEDED", "The request exceeds assistant input limits", false, request));
    }

    @ExceptionHandler(AssistantOutputLimitExceededException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> outputLimitExceeded(
            AssistantOutputLimitExceededException exception, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(error("OUTPUT_TOKEN_LIMIT_EXCEEDED", "The output token request exceeds service limits", false, request));
    }

    @ExceptionHandler(AssistantToolOperationNotAllowedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> toolOperationNotAllowed(
            AssistantToolOperationNotAllowedException exception, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(error("TOOL_OPERATION_NOT_ALLOWED", "The requested tool operation is not allowed", false, request));
    }

    @ExceptionHandler(AssistantToolConfirmationRequiredException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> toolConfirmationRequired(
            AssistantToolConfirmationRequiredException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("TOOL_CONFIRMATION_REQUIRED", "Explicit tool confirmation is required", false, request));
    }

    @ExceptionHandler(AssistantToolConfirmationConflictException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> toolConfirmationConflict(
            AssistantToolConfirmationConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("TOOL_CONFIRMATION_CONFLICT", "The confirmation key conflicts with the requested tool", false, request));
    }

    @ExceptionHandler(AssistantToolConfirmationUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> toolConfirmationUnavailable(
            AssistantToolConfirmationUnavailableException exception, HttpServletRequest request) {
        return unavailable("TOOL_CONFIRMATION_UNAVAILABLE", "Tool confirmation is temporarily unavailable", request);
    }

    @ExceptionHandler(AssistantTaskToolDeniedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> toolDenied(
            AssistantTaskToolDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("TOOL_NOT_AUTHORIZED", "The requested tool action is not authorized", false, request));
    }

    @ExceptionHandler(AssistantTaskToolUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> toolUnavailable(
            AssistantTaskToolUnavailableException exception, HttpServletRequest request) {
        return unavailable("TOOL_UNAVAILABLE", "The task tool is temporarily unavailable", request);
    }

    @ExceptionHandler(AssistantProviderNotConfiguredException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> providerNotConfigured(
            AssistantProviderNotConfiguredException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("AI_PROVIDER_NOT_CONFIGURED", "AI generation is not configured in this deployment", false, request));
    }

    @ExceptionHandler(AssistantProviderTimeoutException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> providerTimeout(
            AssistantProviderTimeoutException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(error("AI_PROVIDER_TIMEOUT", "AI generation timed out", true, request));
    }

    @ExceptionHandler(AssistantProviderBusyException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> providerBusy(
            AssistantProviderBusyException exception, HttpServletRequest request) {
        return unavailable("AI_PROVIDER_UNAVAILABLE", "AI generation is temporarily unavailable", request);
    }

    @ExceptionHandler(GroundedAnswerUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> groundedAnswerUnavailable(
            GroundedAnswerUnavailableException exception, HttpServletRequest request) {
        return unavailable(
                "GROUNDED_ANSWER_UNAVAILABLE",
                "Grounded document answering is temporarily unavailable",
                request);
    }

    @ExceptionHandler(GroundedDocumentDeniedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> groundedDocumentDenied(
            GroundedDocumentDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("DOCUMENT_AI_NOT_AUTHORIZED", "Document AI access is not authorized", false, request));
    }

    @ExceptionHandler(AssistantRecommendationDeniedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> recommendationDenied(
            AssistantRecommendationDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("RECOMMENDATIONS_NOT_AUTHORIZED", "Planning recommendations are not authorized", false, request));
    }

    @ExceptionHandler(AssistantRecommendationUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> recommendationUnavailable(
            AssistantRecommendationUnavailableException exception, HttpServletRequest request) {
        return unavailable(
                "RECOMMENDATIONS_UNAVAILABLE",
                "Goal planning recommendations are temporarily unavailable",
                request);
    }

    @ExceptionHandler(AssistantFinanceDeniedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> financeDenied(
            AssistantFinanceDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("FINANCE_INSIGHTS_NOT_AUTHORIZED", "Financial insights are not authorized", false, request));
    }

    @ExceptionHandler(AssistantFinanceUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> financeUnavailable(
            AssistantFinanceUnavailableException exception, HttpServletRequest request) {
        return unavailable("FINANCE_INSIGHTS_UNAVAILABLE", "Financial insights are temporarily unavailable", request);
    }

    @ExceptionHandler(AssistantJournalDeniedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> journalDenied(
            AssistantJournalDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("JOURNAL_SUMMARY_NOT_AUTHORIZED", "Journal summarization is not authorized", false, request));
    }

    @ExceptionHandler(AssistantJournalUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> journalUnavailable(
            AssistantJournalUnavailableException exception, HttpServletRequest request) {
        return unavailable("JOURNAL_SUMMARY_UNAVAILABLE", "Journal summarization is temporarily unavailable", request);
    }

    @ExceptionHandler(AssistantAnalyticsDeniedException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> analyticsDenied(
            AssistantAnalyticsDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(error("ANALYTICS_RECOMMENDATIONS_NOT_AUTHORIZED", "Analytics recommendations are not authorized", false, request));
    }

    @ExceptionHandler(AssistantAnalyticsUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> analyticsUnavailable(
            AssistantAnalyticsUnavailableException exception, HttpServletRequest request) {
        return unavailable("ANALYTICS_RECOMMENDATIONS_UNAVAILABLE", "Analytics recommendations are temporarily unavailable", request);
    }

    @ExceptionHandler(ConversationHistoryUnavailableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> conversationHistoryUnavailable(
            ConversationHistoryUnavailableException exception, HttpServletRequest request) {
        return unavailable(
                "ASSISTANT_HISTORY_UNAVAILABLE",
                "Conversation history is not available in this deployment",
                request);
    }

    @ExceptionHandler({AssistantProviderFailureException.class, AssistantAuditUnavailableException.class})
    public ResponseEntity<AssistantDtos.ErrorResponse> providerFailure(
            RuntimeException exception, HttpServletRequest request) {
        return unavailable("AI_REQUEST_UNAVAILABLE", "The assistant request is temporarily unavailable", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AssistantDtos.ErrorResponse> unreadableRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        if (hasCause(exception, AssistantPayloadTooLargeException.class)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(error("PAYLOAD_TOO_LARGE", "Request payload too large", false, request));
        }
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Invalid request", false, request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<AssistantDtos.ErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Invalid request", false, request));
    }

    private static ResponseEntity<AssistantDtos.ErrorResponse> unavailable(
            String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(error(code, message, true, request));
    }

    private static AssistantDtos.ErrorResponse error(
            String code, String message, boolean retryable, HttpServletRequest request) {
        Object correlationId = request.getAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE);
        return new AssistantDtos.ErrorResponse(
                code,
                message,
                retryable,
                correlationId instanceof String value ? value : "unbound");
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> expectedType) {
        Throwable current = exception;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
