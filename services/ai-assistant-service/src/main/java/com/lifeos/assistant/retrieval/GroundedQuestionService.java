package com.lifeos.assistant.retrieval;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.journal.AssistantJournalClient;
import com.lifeos.assistant.journal.AssistantJournalDeniedException;
import com.lifeos.assistant.journal.AssistantJournalUnavailableException;
import com.lifeos.assistant.observability.RequestContext;
import com.lifeos.assistant.provider.AssistantGenerationService;
import com.lifeos.assistant.provider.AssistantProviderRequest;
import com.lifeos.assistant.provider.AssistantProviderResponse;
import com.lifeos.assistant.provider.AssistantProviderBusyException;
import com.lifeos.assistant.provider.AssistantProviderFailureException;
import com.lifeos.assistant.provider.AssistantProviderNotConfiguredException;
import com.lifeos.assistant.provider.AssistantProviderTimeoutException;
import com.lifeos.assistant.safety.AssistantPromptDisposition;
import com.lifeos.assistant.safety.AssistantPromptSafetyAssessment;
import com.lifeos.assistant.safety.AssistantPromptSafetyService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Retrieves only owner-filtered chunks and asks the configured provider to cite that evidence. */
@Service
public class GroundedQuestionService {

    private final QdrantVectorStore vectorStore;
    private final AssistantPromptSafetyService safetyService;
    private final AssistantGenerationService generationService;
    private final AssistantAuditService auditService;
    private final AiAssistantProperties properties;
    private final AssistantJournalClient profileClient;

    public GroundedQuestionService(
            QdrantVectorStore vectorStore,
            AssistantPromptSafetyService safetyService,
            AssistantGenerationService generationService,
            AssistantAuditService auditService,
            AiAssistantProperties properties,
            AssistantJournalClient profileClient) {
        this.vectorStore = vectorStore;
        this.safetyService = safetyService;
        this.generationService = generationService;
        this.auditService = auditService;
        this.properties = properties;
        this.profileClient = profileClient;
    }

    public GroundedAnswer answer(AssistantSubject subject, String query, Integer requestedOutputTokens, int limit) {
        long started = System.nanoTime();
        requireDocumentConsent(subject);
        AssistantPromptSafetyAssessment safety = safetyService.assess(query);
        if (safety.disposition() != AssistantPromptDisposition.SAFE) {
            throw new GroundedAnswerUnavailableException();
        }
        QdrantVectorStore.SearchResult search = vectorStore.search(subject.accountId(), query, limit);
        if (!search.available()) {
            audit(subject, query, safety, List.of(), AssistantAuditOutcome.PROVIDER_FAILED, "NOT_GENERATED", 0, started);
            throw new GroundedAnswerUnavailableException();
        }
        if (search.chunks().isEmpty()) {
            audit(subject, query, safety, List.of(), AssistantAuditOutcome.ALLOWED, "INSUFFICIENT_EVIDENCE", 0, started);
            return new GroundedAnswer(
                    "I could not find enough indexed evidence in your documents to answer that safely.",
                    List.of(), false, true, "NONE", "NONE", null);
        }
        int outputTokens = requestedOutputTokens == null ? properties.getMaxOutputTokens() : requestedOutputTokens;
        if (outputTokens < 1 || outputTokens > properties.getMaxOutputTokens()) {
            throw new GroundedAnswerUnavailableException();
        }
        String context = search.chunks().stream()
                .map(chunk -> "[source=" + chunk.documentId() + ",chunk=" + chunk.chunkId() + "] " + chunk.snippet())
                .collect(Collectors.joining("\n"));
        AssistantProviderResponse response;
        try {
            response = generationService.generate(new AssistantProviderRequest(
                    "assistant-grounded-document-answer-v1",
                    com.lifeos.assistant.conversation.AssistantConversationPurpose.GENERAL,
                    "Answer only from the evidence below. If it is insufficient, say so. Include source UUIDs.\n"
                            + "Question: " + safety.providerPrompt() + "\nEvidence:\n" + context,
                    outputTokens));
        } catch (AssistantProviderNotConfiguredException exception) {
            audit(subject, query, safety, sourceIds(search), AssistantAuditOutcome.PROVIDER_NOT_CONFIGURED,
                    "NOT_GENERATED", 0, started, null);
            throw exception;
        } catch (AssistantProviderTimeoutException | AssistantProviderBusyException exception) {
            audit(subject, query, safety, sourceIds(search), AssistantAuditOutcome.PROVIDER_TIMEOUT,
                    "NOT_GENERATED", 0, started, null);
            throw exception;
        } catch (AssistantProviderFailureException exception) {
            audit(subject, query, safety, sourceIds(search), AssistantAuditOutcome.PROVIDER_FAILED,
                    "NOT_GENERATED", 0, started, null);
            throw exception;
        }
        List<UUID> sourceIds = search.chunks().stream().map(QdrantVectorStore.RetrievedChunk::documentId).distinct().toList();
        audit(subject, query, safety, sourceIds, AssistantAuditOutcome.ALLOWED, "GROUNDED_ANSWER", response.text().length(), started, response.text());
        return new GroundedAnswer(
                response.text(), sourceIds, true, false, response.providerId(), response.modelName(), response.confidenceScore());
    }

    public DocumentSummary summarize(AssistantSubject subject, UUID documentId, Integer requestedOutputTokens) {
        long started = System.nanoTime();
        requireDocumentConsent(subject);
        QdrantVectorStore.SearchResult search = vectorStore.fetchDocument(subject.accountId(), documentId);
        if (!search.available() || search.chunks().isEmpty()) {
            throw new GroundedAnswerUnavailableException();
        }
        long version = search.chunks().stream()
                .mapToLong(QdrantVectorStore.RetrievedChunk::documentVersion)
                .max()
                .orElse(0L);
        List<QdrantVectorStore.RetrievedChunk> chunks = search.chunks().stream()
                .filter(chunk -> chunk.documentId().equals(documentId))
                .filter(chunk -> chunk.documentVersion() == version)
                .limit(8)
                .toList();
        if (version < 0L || chunks.isEmpty()) {
            throw new GroundedAnswerUnavailableException();
        }
        String context = chunks.stream()
                .map(chunk -> "[document=" + chunk.documentId() + ",chunk=" + chunk.chunkId() + "] " + chunk.snippet())
                .collect(Collectors.joining("\n"));
        int outputTokens = requestedOutputTokens == null ? properties.getMaxOutputTokens() : requestedOutputTokens;
        if (outputTokens < 1 || outputTokens > properties.getMaxOutputTokens()) {
            throw new GroundedAnswerUnavailableException();
        }
        AssistantPromptSafetyAssessment safety = safetyService.assess("Summarize document " + documentId);
        AssistantProviderResponse response;
        try {
            response = generationService.generate(new AssistantProviderRequest(
                    "assistant-document-summary-v1",
                    com.lifeos.assistant.conversation.AssistantConversationPurpose.GENERAL,
                    "Summarize only the document evidence below. Do not infer missing facts.\n" + context,
                    outputTokens));
        } catch (AssistantProviderNotConfiguredException exception) {
            audit(subject, documentId.toString(), safety, List.of(documentId), AssistantAuditOutcome.PROVIDER_NOT_CONFIGURED,
                    "NOT_GENERATED", 0, started, null);
            throw exception;
        } catch (AssistantProviderTimeoutException | AssistantProviderBusyException exception) {
            audit(subject, documentId.toString(), safety, List.of(documentId), AssistantAuditOutcome.PROVIDER_TIMEOUT,
                    "NOT_GENERATED", 0, started, null);
            throw exception;
        } catch (AssistantProviderFailureException exception) {
            audit(subject, documentId.toString(), safety, List.of(documentId), AssistantAuditOutcome.PROVIDER_FAILED,
                    "NOT_GENERATED", 0, started, null);
            throw exception;
        }
        List<UUID> chunkIds = chunks.stream().map(QdrantVectorStore.RetrievedChunk::chunkId).toList();
        audit(subject, documentId.toString(), safety, List.of(documentId), AssistantAuditOutcome.ALLOWED,
                "DOCUMENT_SUMMARY", response.text().length(), started, response.text());
        return new DocumentSummary(
                documentId,
                version,
                chunkIds,
                response.text(),
                response.providerId(),
                response.modelName(),
                response.confidenceScore());
    }

    private void requireDocumentConsent(AssistantSubject subject) {
        try {
            AssistantJournalClient.PersonalizationSnapshot consent = profileClient.personalization(subject);
            if (!consent.consentGranted()
                    || !consent.personalizationEnabled()
                    || consent.allowedContextCategories() == null
                    || !consent.allowedContextCategories().contains("DOCUMENTS")) {
                throw new GroundedDocumentDeniedException();
            }
        } catch (AssistantJournalDeniedException exception) {
            throw new GroundedDocumentDeniedException();
        } catch (AssistantJournalUnavailableException exception) {
            throw new GroundedAnswerUnavailableException();
        }
    }

    private void audit(
            AssistantSubject subject,
            String query,
            AssistantPromptSafetyAssessment safety,
            List<UUID> sourceIds,
            AssistantAuditOutcome outcome,
            String summary,
            int outputCharacters,
            long started) {
        audit(subject, query, safety, sourceIds, outcome, summary, outputCharacters, started, null);
    }

    private void audit(
            AssistantSubject subject,
            String query,
            AssistantPromptSafetyAssessment safety,
            List<UUID> sourceIds,
            AssistantAuditOutcome outcome,
            String summary,
            int outputCharacters,
            long started,
            String outputForFingerprintOnly) {
        String correlationId = RequestContext.CORRELATION_ID.isBound() ? RequestContext.CORRELATION_ID.get() : "unbound";
        auditService.record(new AssistantAuditRecord(
                null,
                subject.accountId(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                outcome,
                "assistant-grounded-document-answer-v1",
                query,
                safety.inputCharacters(),
                safety.estimatedInputTokens(),
                properties.getMaxOutputTokens(),
                contextIds(sourceIds),
                safety.flags().isEmpty() ? "NONE" : safety.flags().stream().map(Enum::name).sorted().collect(Collectors.joining(",")),
                summary.equals("GROUNDED_ANSWER") ? generationService.providerId() : "not-invoked",
                summary.equals("GROUNDED_ANSWER") ? generationService.modelName() : "not-invoked",
                summary,
                outputForFingerprintOnly,
                outputCharacters,
                null,
                "NONE",
                "NOT_REQUESTED",
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
                correlationId));
    }

    private static List<UUID> sourceIds(QdrantVectorStore.SearchResult search) {
        return search.chunks().stream().map(QdrantVectorStore.RetrievedChunk::documentId).distinct().toList();
    }

    private static String contextIds(List<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return "NONE";
        }
        return sourceIds.stream().limit(12).map(UUID::toString).collect(Collectors.joining(","));
    }

    public record GroundedAnswer(
            String content,
            List<UUID> sourceDocumentIds,
            boolean evidenceSufficient,
            boolean degraded,
            String providerId,
            String modelName,
            java.math.BigDecimal confidenceScore) {
    }

    public record DocumentSummary(
            UUID documentId,
            long documentVersion,
            List<UUID> sourceChunkIds,
            String content,
            String providerId,
            String modelName,
            java.math.BigDecimal confidenceScore) {
    }
}
