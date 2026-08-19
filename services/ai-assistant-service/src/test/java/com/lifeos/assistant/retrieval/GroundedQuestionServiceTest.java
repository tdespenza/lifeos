package com.lifeos.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.journal.AssistantJournalClient;
import com.lifeos.assistant.provider.AssistantGenerationService;
import com.lifeos.assistant.provider.AssistantProviderResponse;
import com.lifeos.assistant.safety.AssistantPromptSafetyService;
import com.lifeos.assistant.safety.AssistantPromptSafetyAssessment;
import com.lifeos.assistant.safety.AssistantPromptDisposition;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroundedQuestionServiceTest {

    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "PASSWORD", "a".repeat(64));

    @Test
    void documentSummaryRequiresExplicitDocumentsConsentBeforeRetrieval() {
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        AssistantJournalClient profileClient = mock(AssistantJournalClient.class);
        when(profileClient.personalization(SUBJECT))
                .thenReturn(new AssistantJournalClient.PersonalizationSnapshot(true, true, List.of("JOURNALS")));
        GroundedQuestionService service = service(vectorStore, profileClient);

        assertThatThrownBy(() -> service.summarize(SUBJECT, UUID.randomUUID(), null))
                .isInstanceOf(GroundedDocumentDeniedException.class);
        assertThatThrownBy(() -> service.answer(SUBJECT, "private question", null, 8))
                .isInstanceOf(GroundedDocumentDeniedException.class);
        verifyNoInteractions(vectorStore);
    }

    @Test
    void summaryUsesOneLatestDocumentVersionAndReportsChunkSources() {
        UUID documentId = UUID.randomUUID();
        UUID latestChunk = UUID.randomUUID();
        UUID staleChunk = UUID.randomUUID();
        UUID unrelatedDocument = UUID.randomUUID();
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        AssistantJournalClient profileClient = mock(AssistantJournalClient.class);
        AssistantGenerationService generation = mock(AssistantGenerationService.class);
        when(profileClient.personalization(SUBJECT))
                .thenReturn(new AssistantJournalClient.PersonalizationSnapshot(true, true, List.of("DOCUMENTS")));
        when(vectorStore.fetchDocument(SUBJECT.accountId(), documentId)).thenReturn(QdrantVectorStore.SearchResult.available(List.of(
                new QdrantVectorStore.RetrievedChunk(documentId, staleChunk, "old", 0.9, 1L),
                new QdrantVectorStore.RetrievedChunk(documentId, latestChunk, "current", 0.8, 2L),
                new QdrantVectorStore.RetrievedChunk(unrelatedDocument, UUID.randomUUID(), "ignored", 0.7, 2L))));
        when(generation.generate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AssistantProviderResponse("summary", "local-deterministic", "rules-v1", BigDecimal.ONE));
        GroundedQuestionService service = service(vectorStore, profileClient, generation);

        GroundedQuestionService.DocumentSummary summary = service.summarize(SUBJECT, documentId, 64);

        assertThat(summary.documentId()).isEqualTo(documentId);
        assertThat(summary.documentVersion()).isEqualTo(2L);
        assertThat(summary.sourceChunkIds()).containsExactly(latestChunk);
        assertThat(summary.content()).isEqualTo("summary");
    }

    private static GroundedQuestionService service(
            QdrantVectorStore vectorStore, AssistantJournalClient profileClient) {
        return service(vectorStore, profileClient, mock(AssistantGenerationService.class));
    }

    private static GroundedQuestionService service(
            QdrantVectorStore vectorStore,
            AssistantJournalClient profileClient,
            AssistantGenerationService generation) {
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setMaxOutputTokens(256);
        AssistantPromptSafetyService safety = mock(AssistantPromptSafetyService.class);
        when(safety.assess(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new AssistantPromptSafetyAssessment("safe", 4, 2, java.util.Set.of(), AssistantPromptDisposition.SAFE));
        return new GroundedQuestionService(
                vectorStore,
                safety,
                generation,
                mock(AssistantAuditService.class),
                properties,
                profileClient);
    }
}
