package com.lifeos.assistant.audit;

import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.observability.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists auditable outcomes through keyed fingerprints without retaining personal content. */
@Service
public class AssistantAuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final AssistantRequestAuditEventRepository repository;
    private final AiAuditHashOutboxEventRepository outboxRepository;
    private final AiAuditHashEventFactory eventFactory;
    private final SecretKeySpec fingerprintKey;

    public AssistantAuditService(
            AssistantRequestAuditEventRepository repository,
            AiAuditHashOutboxEventRepository outboxRepository,
            AiAuditHashEventFactory eventFactory,
            AiAssistantProperties properties) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        fingerprintKey = new SecretKeySpec(
                properties.getAuditHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** Audit persistence is part of the safety boundary; a failure produces a fail-closed result. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AssistantAuditRecord record) {
        try {
            String clientAddress = RequestContext.CLIENT_ADDRESS.isBound()
                    ? RequestContext.CLIENT_ADDRESS.get()
                    : "unknown";
            String inputFingerprint = fingerprintNullable(record.inputForFingerprintOnly());
            String outputFingerprint = fingerprintNullable(record.outputForFingerprintOnly());
            String clientFingerprint = digest(clientAddress);
            AssistantRequestAuditEvent auditEvent = repository.saveAndFlush(new AssistantRequestAuditEvent(
                    record,
                    inputFingerprint,
                    outputFingerprint,
                    clientFingerprint,
                    auditHash(record, inputFingerprint, outputFingerprint, clientFingerprint)));
            outboxRepository.saveAndFlush(new AiAuditHashOutboxEvent(
                    auditEvent,
                    com.lifeos.events.v1.EventContract.AI_AUDIT_HASH_REQUESTED_V1_TYPE,
                    com.lifeos.events.v1.EventContract.AI_AUDIT_HASH_REQUESTED_V1_TOPIC,
                    eventFactory.createPayload(auditEvent),
                    auditEvent.getOccurredAt()));
        } catch (AssistantAuditUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AssistantAuditUnavailableException(exception);
        }
    }

    /** Records an authentication-boundary failure without ever fingerprinting a bearer token. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthenticationFailure(AssistantAuditOutcome outcome) {
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        record(new AssistantAuditRecord(
                null,
                null,
                AssistantAuditRequestKind.AUTHENTICATION,
                outcome,
                "assistant-authentication-v1",
                null,
                0,
                0,
                0,
                "NONE",
                "NONE",
                "not-invoked",
                "not-invoked",
                "NOT_GENERATED",
                null,
                0,
                null,
                "NONE",
                "NOT_REQUESTED",
                0,
                correlationId));
    }

    private String fingerprintNullable(String value) {
        return value == null ? null : digest(value);
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new AssistantAuditUnavailableException(exception);
        }
    }

    /**
     * Computes a stable commitment over bounded, already-redacted fields only. Length-prefixing
     * avoids delimiter ambiguity while preserving null values; raw prompt/output values never
     * enter this canonical form.
     */
    private String auditHash(
            AssistantAuditRecord record,
            String inputFingerprint,
            String outputFingerprint,
            String clientFingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            StringBuilder canonical = new StringBuilder(768);
            append(canonical, record.conversationId());
            append(canonical, record.ownerAccountId());
            append(canonical, record.requestKind());
            append(canonical, record.outcome());
            append(canonical, record.promptTemplateId());
            append(canonical, inputFingerprint);
            append(canonical, record.inputCharacters());
            append(canonical, record.estimatedInputTokens());
            append(canonical, record.requestedOutputTokens());
            append(canonical, record.retrievedContextIds());
            append(canonical, record.safetyFlags());
            append(canonical, record.providerId());
            append(canonical, record.modelName());
            append(canonical, record.outputSummary());
            append(canonical, outputFingerprint);
            append(canonical, record.outputCharacters());
            append(canonical, record.confidenceScore());
            append(canonical, record.toolOperation());
            append(canonical, record.toolExecutionState());
            append(canonical, record.latencyMillis());
            append(canonical, record.correlationId());
            append(canonical, clientFingerprint);
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssistantAuditUnavailableException(exception);
        }
    }

    private static void append(StringBuilder canonical, Object value) {
        if (value == null) {
            canonical.append("-1:");
            return;
        }
        String text = value instanceof java.math.BigDecimal decimal
                ? decimal.toPlainString()
                : value.toString();
        canonical.append(text.length()).append(':').append(text);
    }
}
