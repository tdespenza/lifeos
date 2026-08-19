package com.lifeos.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantRequestAuditEvent;
import com.lifeos.assistant.audit.AssistantRequestAuditEventRepository;
import com.lifeos.assistant.audit.AiAuditHashOutboxEvent;
import com.lifeos.assistant.audit.AiAuditHashOutboxEventRepository;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.provider.AssistantGenerationService;
import com.lifeos.assistant.provider.AssistantProviderResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Covers audit-safe provider response metadata without retaining an output body. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-response-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=assistant-response-audit-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "ai-assistant.audit-hmac-secret=assistant-response-audit-secret",
    "identity.workload-token=assistant-response-audit-workload-token"
})
class AssistantResponseAuditIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Autowired
    private AssistantConversationService service;

    @Autowired
    private AssistantConversationRepository conversationRepository;

    @Autowired
    private AssistantRequestAuditEventRepository auditRepository;

    @Autowired
    private AiAuditHashOutboxEventRepository auditOutboxRepository;

    @MockitoBean
    private AssistantGenerationService generationService;

    private AssistantSubject subject;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        auditOutboxRepository.deleteAll();
        conversationRepository.deleteAll();
        subject = new AssistantSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(generationService.generate(any())).thenReturn(new AssistantProviderResponse(
                "A private completion returned only to this caller", "test-provider", "test-model", new BigDecimal("0.8200")));
    }

    @Test
    void recordsAClassificationFingerprintAndConfidenceInsteadOfTheCompletionText() {
        AssistantConversation conversation = service.createConversation(subject, AssistantConversationPurpose.GENERAL);

        AssistantGenerationResult result = service.requestResponse(
                subject, conversation.getId(), "Help me plan", 64, "DRAFT_TASK");

        AssistantRequestAuditEvent auditEvent = auditRepository.findAll().stream()
                .filter(event -> event.getRequestKind() == AssistantAuditRequestKind.GENERATION_REQUEST)
                .findFirst()
                .orElseThrow();
        assertThat(result.content()).isEqualTo("A private completion returned only to this caller");
        assertThat(result.confidenceScore()).isEqualByComparingTo("0.8200");
        assertThat(auditEvent.getOutputSummary()).isEqualTo("OUTPUT_RETURNED_ONCE");
        assertThat(auditEvent.getOutputFingerprint()).matches("[0-9a-f]{64}");
        assertThat(auditEvent.getAuditHashSha256()).matches("[0-9a-f]{64}");
        AiAuditHashOutboxEvent outboxEvent = auditOutboxRepository.findById(auditEvent.getId()).orElseThrow();
        assertThat(outboxEvent.getAuditHashSha256()).isEqualTo(auditEvent.getAuditHashSha256());
        assertThat(outboxEvent.getEventType()).isEqualTo("com.lifeos.ai.audit.hash.requested.v1");
        assertThat(outboxEvent.getPayloadJson())
                .contains(auditEvent.getAuditHashSha256())
                .doesNotContain("A private completion returned only to this caller");
        assertThat(auditEvent.toString()).doesNotContain("A private completion returned only to this caller");
    }
}
