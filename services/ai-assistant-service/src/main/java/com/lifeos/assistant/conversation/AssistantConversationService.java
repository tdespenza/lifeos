package com.lifeos.assistant.conversation;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.history.AssistantConversationHistoryStore;
import com.lifeos.assistant.observability.RequestContext;
import com.lifeos.assistant.provider.AssistantGenerationService;
import com.lifeos.assistant.provider.AssistantProviderBusyException;
import com.lifeos.assistant.provider.AssistantProviderFailureException;
import com.lifeos.assistant.provider.AssistantProviderNotConfiguredException;
import com.lifeos.assistant.provider.AssistantProviderRequest;
import com.lifeos.assistant.provider.AssistantProviderResponse;
import com.lifeos.assistant.provider.AssistantProviderTimeoutException;
import com.lifeos.assistant.safety.AssistantPromptDisposition;
import com.lifeos.assistant.safety.AssistantPromptSafetyAssessment;
import com.lifeos.assistant.safety.AssistantPromptSafetyService;
import com.lifeos.assistant.safety.AssistantSafetyFlag;
import com.lifeos.assistant.tool.AssistantToolOperation;
import com.lifeos.assistant.tool.AssistantToolOperationNotAllowedException;
import com.lifeos.assistant.tool.AssistantToolPlan;
import com.lifeos.assistant.tool.AssistantToolPolicy;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Owner-scoped assistant interaction lifecycle. Conversation content remains ephemeral by default;
 * an explicitly configured history store may retain bounded, encrypted, PII-redacted messages.
 */
@Service
public class AssistantConversationService {

    private static final String NO_SAFETY_FLAGS = "NONE";
    private static final String NO_TOOL = "NONE";
    private static final String NO_PROVIDER = "not-invoked";
    private static final String NO_MODEL = "not-invoked";
    private static final String NO_RETRIEVED_CONTEXT = "NONE";
    private static final String NOT_GENERATED = "NOT_GENERATED";
    private static final String OUTPUT_RETURNED_ONCE = "OUTPUT_RETURNED_ONCE";

    private final AssistantConversationRepository conversationRepository;
    private final AssistantPromptSafetyService promptSafetyService;
    private final AssistantToolPolicy toolPolicy;
    private final AssistantGenerationService generationService;
    private final AssistantAuditService auditService;
    private final AssistantRequestMetrics metrics;
    private final AiAssistantProperties properties;
    private final AssistantConversationHistoryStore historyStore;

    public AssistantConversationService(
            AssistantConversationRepository conversationRepository,
            AssistantPromptSafetyService promptSafetyService,
            AssistantToolPolicy toolPolicy,
            AssistantGenerationService generationService,
            AssistantAuditService auditService,
            AssistantRequestMetrics metrics,
            AiAssistantProperties properties,
            AssistantConversationHistoryStore historyStore) {
        this.conversationRepository = conversationRepository;
        this.promptSafetyService = promptSafetyService;
        this.toolPolicy = toolPolicy;
        this.generationService = generationService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.properties = properties;
        this.historyStore = historyStore;
    }

    public AssistantConversation createConversation(AssistantSubject subject, AssistantConversationPurpose purpose) {
        long started = System.nanoTime();
        AssistantConversation conversation = conversationRepository.saveAndFlush(AssistantConversation.create(subject.accountId(), purpose));
        audit(
                conversation.getId(),
                subject.accountId(),
                AssistantAuditRequestKind.CONVERSATION_CREATE,
                AssistantAuditOutcome.ALLOWED,
                null,
                0,
                0,
                0,
                NO_SAFETY_FLAGS,
                NO_PROVIDER,
                NO_MODEL,
                NO_TOOL,
                "NOT_REQUESTED",
                elapsedMillis(started));
        metrics.record(purpose, "conversation_created");
        return conversation;
    }

    public AssistantConversation readConversation(AssistantSubject subject, UUID conversationId) {
        long started = System.nanoTime();
        AssistantConversation conversation = conversationRepository
                .findByIdAndOwnerAccountId(conversationId, subject.accountId())
                .orElseGet(() -> {
                    audit(
                            conversationId,
                            subject.accountId(),
                            AssistantAuditRequestKind.CONVERSATION_READ,
                            AssistantAuditOutcome.NOT_FOUND,
                            null,
                            0,
                            0,
                            0,
                            NO_SAFETY_FLAGS,
                            NO_PROVIDER,
                            NO_MODEL,
                            NO_TOOL,
                            "NOT_REQUESTED",
                            elapsedMillis(started));
                    metrics.record(AssistantConversationPurpose.GENERAL, "conversation_not_found");
                    throw new AssistantConversationNotFoundException();
                });
        audit(
                conversation.getId(),
                subject.accountId(),
                AssistantAuditRequestKind.CONVERSATION_READ,
                AssistantAuditOutcome.ALLOWED,
                null,
                0,
                0,
                0,
                NO_SAFETY_FLAGS,
                NO_PROVIDER,
                NO_MODEL,
                NO_TOOL,
                "NOT_REQUESTED",
                elapsedMillis(started));
        metrics.record(conversation.getPurpose(), "conversation_read");
        return conversation;
    }

    public AssistantGenerationResult requestResponse(
            AssistantSubject subject,
            UUID conversationId,
            String message,
            Integer requestedOutputTokens,
            String requestedToolOperation) {
        long started = System.nanoTime();
        AssistantConversation conversation = ownedConversation(subject, conversationId, started);
        AssistantPromptSafetyAssessment safety = promptSafetyService.assess(message);
        int outputTokens = resolveOutputTokens(conversation, subject, safety, requestedOutputTokens, started);
        AssistantToolOperation toolOperation = resolveToolOperation(
                conversation, subject, safety, outputTokens, requestedToolOperation, started);
        if (safety.disposition() == AssistantPromptDisposition.REJECT_PROMPT_INJECTION) {
            auditRequest(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    toolOperation,
                    AssistantAuditOutcome.PROMPT_REJECTED,
                    NO_PROVIDER,
                    NO_MODEL,
                    "NOT_EXECUTED",
                    started);
            metrics.record(conversation.getPurpose(), "prompt_rejected");
            throw new AssistantPromptRejectedException();
        }
        if (safety.disposition() == AssistantPromptDisposition.REJECT_INPUT_LIMIT) {
            auditRequest(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    toolOperation,
                    AssistantAuditOutcome.PROMPT_REJECTED,
                    NO_PROVIDER,
                    NO_MODEL,
                    "NOT_EXECUTED",
                    started);
            metrics.record(conversation.getPurpose(), "input_limit_rejected");
            throw new AssistantInputLimitExceededException();
        }
        try {
            AssistantProviderResponse providerResponse = generationService.generate(new AssistantProviderRequest(
                    promptTemplateId(conversation.getPurpose()),
                    conversation.getPurpose(),
                    safety.providerPrompt(),
                    outputTokens));
            AssistantToolPlan toolPlan = toolPolicy.notExecuted(toolOperation, "CROSS_SERVICE_MUTATION_TOOLS_NOT_ENABLED");
            // Retention is an explicit opt-in. The prompt written to history is already PII-redacted;
            // provider output is encrypted by the configured store before it leaves this process.
            historyStore.append(subject.accountId(), conversation.getId(), "user", safety.providerPrompt());
            historyStore.append(subject.accountId(), conversation.getId(), "assistant", providerResponse.text());
            auditSuccessfulResponse(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    toolOperation,
                    providerResponse,
                    toolPlan,
                    started);
            metrics.record(conversation.getPurpose(), "provider_response");
            return new AssistantGenerationResult(
                    conversation.getId(),
                    conversation.getPurpose(),
                    providerResponse.text(),
                    safety.estimatedInputTokens(),
                    outputTokens,
                    flags(safety),
                    toolPlan,
                    providerResponse.providerId(),
                    providerResponse.modelName(),
                    providerResponse.confidenceScore());
        } catch (AssistantProviderNotConfiguredException exception) {
            auditRequest(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    toolOperation,
                    AssistantAuditOutcome.PROVIDER_NOT_CONFIGURED,
                    generationService.providerId(),
                    generationService.modelName(),
                    "NOT_EXECUTED",
                    started);
            metrics.record(conversation.getPurpose(), "provider_not_configured");
            throw exception;
        } catch (AssistantProviderTimeoutException | AssistantProviderBusyException exception) {
            auditRequest(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    toolOperation,
                    AssistantAuditOutcome.PROVIDER_TIMEOUT,
                    generationService.providerId(),
                    generationService.modelName(),
                    "NOT_EXECUTED",
                    started);
            metrics.record(conversation.getPurpose(), "provider_unavailable");
            throw exception;
        } catch (AssistantProviderFailureException exception) {
            auditRequest(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    toolOperation,
                    AssistantAuditOutcome.PROVIDER_FAILED,
                    generationService.providerId(),
                    generationService.modelName(),
                    "NOT_EXECUTED",
                    started);
            metrics.record(conversation.getPurpose(), "provider_failed");
            throw exception;
        }
    }

    public List<AssistantConversationHistoryStore.HistoryEntry> readHistory(
            AssistantSubject subject, UUID conversationId) {
        AssistantConversation conversation = readConversation(subject, conversationId);
        return historyStore.read(conversation.getOwnerAccountId(), conversation.getId());
    }

    public AssistantConversationSummaryResult summarizeConversation(
            AssistantSubject subject, UUID conversationId, Integer requestedOutputTokens) {
        long started = System.nanoTime();
        AssistantConversation conversation = ownedConversation(subject, conversationId, started);
        List<AssistantConversationHistoryStore.HistoryEntry> history = historyStore.read(
                conversation.getOwnerAccountId(), conversation.getId());
        if (history.isEmpty()) {
            throw new com.lifeos.assistant.history.ConversationHistoryUnavailableException(
                    new IllegalStateException("conversation has no retained messages"));
        }
        int outputTokens = requestedOutputTokens == null ? properties.getMaxOutputTokens() : requestedOutputTokens;
        if (outputTokens < 1 || outputTokens > properties.getMaxOutputTokens()) {
            throw new AssistantOutputLimitExceededException();
        }
        String transcript = boundedTranscript(history);
        AssistantPromptSafetyAssessment safety = promptSafetyService.assess(
                "Summarize the following retained conversation in a concise, factual form.\n" + transcript);
        if (safety.disposition() != AssistantPromptDisposition.SAFE) {
            throw new AssistantInputLimitExceededException();
        }
        try {
            AssistantProviderResponse response = generationService.generate(new AssistantProviderRequest(
                    "assistant-session-summary-v1",
                    AssistantConversationPurpose.SESSION_SUMMARY,
                    safety.providerPrompt(),
                    outputTokens));
            auditDetailed(
                    conversation.getId(),
                    subject.accountId(),
                    AssistantAuditRequestKind.GENERATION_REQUEST,
                    AssistantAuditOutcome.ALLOWED,
                    "assistant-session-summary-v1",
                    safety.providerPrompt(),
                    safety.inputCharacters(),
                    safety.estimatedInputTokens(),
                    outputTokens,
                    NO_RETRIEVED_CONTEXT,
                    flagsString(safety),
                    response.providerId(),
                    response.modelName(),
                    OUTPUT_RETURNED_ONCE,
                    response.text(),
                    response.text().codePointCount(0, response.text().length()),
                    response.confidenceScore(),
                    NO_TOOL,
                    "NOT_EXECUTED",
                    elapsedMillis(started));
            metrics.record(AssistantConversationPurpose.SESSION_SUMMARY, "summary_response");
            return new AssistantConversationSummaryResult(
                    conversation.getId(), history.size(), response.text(), response.providerId(),
                    response.modelName(), response.confidenceScore());
        } catch (AssistantProviderNotConfiguredException exception) {
            auditRequest(
                    conversation, subject, safety, outputTokens, AssistantToolOperation.NONE,
                    AssistantAuditOutcome.PROVIDER_NOT_CONFIGURED, generationService.providerId(),
                    generationService.modelName(), "NOT_EXECUTED", started);
            throw exception;
        } catch (AssistantProviderTimeoutException | AssistantProviderBusyException exception) {
            auditRequest(
                    conversation, subject, safety, outputTokens, AssistantToolOperation.NONE,
                    AssistantAuditOutcome.PROVIDER_TIMEOUT, generationService.providerId(),
                    generationService.modelName(), "NOT_EXECUTED", started);
            throw exception;
        } catch (AssistantProviderFailureException exception) {
            auditRequest(
                    conversation, subject, safety, outputTokens, AssistantToolOperation.NONE,
                    AssistantAuditOutcome.PROVIDER_FAILED, generationService.providerId(),
                    generationService.modelName(), "NOT_EXECUTED", started);
            throw exception;
        }
    }

    private static String boundedTranscript(List<AssistantConversationHistoryStore.HistoryEntry> history) {
        StringBuilder transcript = new StringBuilder();
        int start = Math.max(0, history.size() - 16);
        for (int index = start; index < history.size() && transcript.length() < 8_192; index++) {
            AssistantConversationHistoryStore.HistoryEntry entry = history.get(index);
            String content = entry.content();
            int remaining = 8_192 - transcript.length();
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }
            transcript.append(entry.role()).append(": ").append(content).append('\n');
        }
        return transcript.toString();
    }

    private AssistantConversation ownedConversation(AssistantSubject subject, UUID conversationId, long started) {
        return conversationRepository
                .findByIdAndOwnerAccountId(conversationId, subject.accountId())
                .orElseGet(() -> {
                    audit(
                            conversationId,
                            subject.accountId(),
                            AssistantAuditRequestKind.GENERATION_REQUEST,
                            AssistantAuditOutcome.NOT_FOUND,
                            null,
                            0,
                            0,
                            0,
                            NO_SAFETY_FLAGS,
                            NO_PROVIDER,
                            NO_MODEL,
                            NO_TOOL,
                            "NOT_REQUESTED",
                            elapsedMillis(started));
                    metrics.record(AssistantConversationPurpose.GENERAL, "conversation_not_found");
                    throw new AssistantConversationNotFoundException();
                });
    }

    private int resolveOutputTokens(
            AssistantConversation conversation,
            AssistantSubject subject,
            AssistantPromptSafetyAssessment safety,
            Integer requestedOutputTokens,
            long started) {
        int outputTokens = requestedOutputTokens == null ? properties.getMaxOutputTokens() : requestedOutputTokens;
        if (outputTokens >= 1 && outputTokens <= properties.getMaxOutputTokens()) {
            return outputTokens;
        }
        auditRequest(
                conversation,
                subject,
                safety,
                Math.max(0, outputTokens),
                AssistantToolOperation.NONE,
                AssistantAuditOutcome.OUTPUT_LIMIT_REJECTED,
                NO_PROVIDER,
                NO_MODEL,
                "NOT_EXECUTED",
                started);
        metrics.record(conversation.getPurpose(), "output_limit_rejected");
        throw new AssistantOutputLimitExceededException();
    }

    private AssistantToolOperation resolveToolOperation(
            AssistantConversation conversation,
            AssistantSubject subject,
            AssistantPromptSafetyAssessment safety,
            int outputTokens,
            String requestedToolOperation,
            long started) {
        try {
            return toolPolicy.resolve(requestedToolOperation);
        } catch (AssistantToolOperationNotAllowedException exception) {
            auditRequest(
                    conversation,
                    subject,
                    safety,
                    outputTokens,
                    AssistantToolOperation.NONE,
                    AssistantAuditOutcome.TOOL_REJECTED,
                    NO_PROVIDER,
                    NO_MODEL,
                    "NOT_EXECUTED",
                    started);
            metrics.record(conversation.getPurpose(), "tool_rejected");
            throw exception;
        }
    }

    private void auditRequest(
            AssistantConversation conversation,
            AssistantSubject subject,
            AssistantPromptSafetyAssessment safety,
            int outputTokens,
            AssistantToolOperation toolOperation,
            AssistantAuditOutcome outcome,
            String providerId,
            String modelName,
            String toolExecutionState,
            long started) {
        audit(
                conversation.getId(),
                subject.accountId(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                outcome,
                safety.providerPrompt(),
                safety.inputCharacters(),
                safety.estimatedInputTokens(),
                outputTokens,
                flagsString(safety),
                providerId,
                modelName,
                toolOperation.name(),
                toolExecutionState,
                elapsedMillis(started));
    }

    private void auditSuccessfulResponse(
            AssistantConversation conversation,
            AssistantSubject subject,
            AssistantPromptSafetyAssessment safety,
            int outputTokens,
            AssistantToolOperation toolOperation,
            AssistantProviderResponse providerResponse,
            AssistantToolPlan toolPlan,
            long started) {
        auditDetailed(
                conversation.getId(),
                subject.accountId(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                AssistantAuditOutcome.ALLOWED,
                promptTemplateId(conversation.getPurpose()),
                safety.providerPrompt(),
                safety.inputCharacters(),
                safety.estimatedInputTokens(),
                outputTokens,
                NO_RETRIEVED_CONTEXT,
                flagsString(safety),
                providerResponse.providerId(),
                providerResponse.modelName(),
                OUTPUT_RETURNED_ONCE,
                providerResponse.text(),
                providerResponse.text().codePointCount(0, providerResponse.text().length()),
                providerResponse.confidenceScore(),
                toolOperation.name(),
                toolPlan.executionState(),
                elapsedMillis(started));
    }

    private void audit(
            UUID conversationId,
            UUID ownerAccountId,
            AssistantAuditRequestKind requestKind,
            AssistantAuditOutcome outcome,
            String inputForFingerprintOnly,
            int inputCharacters,
            int estimatedInputTokens,
            int requestedOutputTokens,
            String safetyFlags,
            String providerId,
            String modelName,
            String toolOperation,
            String toolExecutionState,
            long latencyMillis) {
        auditDetailed(
                conversationId,
                ownerAccountId,
                requestKind,
                outcome,
                promptTemplateId(requestKind),
                inputForFingerprintOnly,
                inputCharacters,
                estimatedInputTokens,
                requestedOutputTokens,
                NO_RETRIEVED_CONTEXT,
                safetyFlags,
                providerId,
                modelName,
                NOT_GENERATED,
                null,
                0,
                null,
                toolOperation,
                toolExecutionState,
                latencyMillis);
    }

    private void auditDetailed(
            UUID conversationId,
            UUID ownerAccountId,
            AssistantAuditRequestKind requestKind,
            AssistantAuditOutcome outcome,
            String promptTemplateId,
            String inputForFingerprintOnly,
            int inputCharacters,
            int estimatedInputTokens,
            int requestedOutputTokens,
            String retrievedContextIds,
            String safetyFlags,
            String providerId,
            String modelName,
            String outputSummary,
            String outputForFingerprintOnly,
            int outputCharacters,
            BigDecimal confidenceScore,
            String toolOperation,
            String toolExecutionState,
            long latencyMillis) {
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        auditService.record(new AssistantAuditRecord(
                conversationId,
                ownerAccountId,
                requestKind,
                outcome,
                promptTemplateId,
                inputForFingerprintOnly,
                inputCharacters,
                estimatedInputTokens,
                requestedOutputTokens,
                retrievedContextIds,
                safetyFlags,
                providerId,
                modelName,
                outputSummary,
                outputForFingerprintOnly,
                outputCharacters,
                confidenceScore,
                toolOperation,
                toolExecutionState,
                latencyMillis,
                correlationId));
    }

    private static String promptTemplateId(AssistantConversationPurpose purpose) {
        return "assistant-" + purpose.name().toLowerCase().replace('_', '-') + "-v1";
    }

    private static String promptTemplateId(AssistantAuditRequestKind requestKind) {
        return "assistant-" + requestKind.name().toLowerCase().replace('_', '-') + "-v1";
    }

    private static List<String> flags(AssistantPromptSafetyAssessment safety) {
        return safety.flags().stream().map(Enum::name).sorted().toList();
    }

    private static String flagsString(AssistantPromptSafetyAssessment safety) {
        return safety.flags().isEmpty()
                ? NO_SAFETY_FLAGS
                : safety.flags().stream().map(Enum::name).sorted(Comparator.naturalOrder()).reduce((left, right) -> left + "," + right).orElse(NO_SAFETY_FLAGS);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
