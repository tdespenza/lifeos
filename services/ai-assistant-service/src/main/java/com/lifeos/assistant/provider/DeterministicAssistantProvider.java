package com.lifeos.assistant.provider;

import com.lifeos.assistant.config.AiAssistantProperties;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * A bounded, dependency-free local provider for development and deterministic contract tests.
 *
 * <p>This is deliberately extractive rather than generative: it returns a stable, redacted
 * excerpt of the already-sanitized prompt and never calls a network, reads a database, or claims
 * model-quality inference. Deployments that need actual generation must select the reviewed
 * OpenAI-compatible adapter instead.
 */
public final class DeterministicAssistantProvider implements AssistantProvider {

    private static final String PROVIDER_ID = "local-deterministic";
    private static final String MODEL = "rules-v1";
    private static final int MAX_OUTPUT_CHARACTERS = 4_096;

    private final AiAssistantProperties.Provider properties;

    public DeterministicAssistantProvider(AiAssistantProperties.Provider properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public boolean isConfigured() {
        return properties.getMode() == AiAssistantProperties.ProviderMode.LOCAL_DETERMINISTIC;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String modelName() {
        return MODEL;
    }

    @Override
    public AssistantProviderResponse generate(AssistantProviderRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!isConfigured()) {
            throw new AssistantProviderNotConfiguredException();
        }
        String prompt = request.redactedPrompt() == null ? "" : request.redactedPrompt().strip();
        if (!StringUtils.hasText(prompt)) {
            throw new AssistantProviderFailureException(null);
        }
        int maximumCharacters = Math.min(MAX_OUTPUT_CHARACTERS, Math.max(32, request.maxOutputTokens() * 4));
        String excerpt = extractiveExcerpt(prompt, maximumCharacters - 32);
        String prefix = request.promptTemplateId().contains("summary")
                ? "Local deterministic summary: "
                : request.promptTemplateId().contains("grounded")
                        ? "Local deterministic evidence answer: "
                        : "Local deterministic response: ";
        String output = (prefix + excerpt).strip();
        if (output.length() > maximumCharacters) {
            output = output.substring(0, maximumCharacters).strip();
        }
        return new AssistantProviderResponse(output, providerId(), modelName(), BigDecimal.ONE);
    }

    private static String extractiveExcerpt(String prompt, int maximumCharacters) {
        String candidate = prompt;
        int question = candidate.indexOf("Question:");
        if (question >= 0 && question + "Question:".length() < candidate.length()) {
            candidate = candidate.substring(question + "Question:".length()).strip();
            int evidence = candidate.indexOf("Evidence:");
            if (evidence >= 0) {
                candidate = candidate.substring(0, evidence).strip();
            }
        } else {
            int evidence = candidate.indexOf("Evidence:");
            if (evidence >= 0) {
                candidate = candidate.substring(evidence + "Evidence:".length()).strip();
            }
        }
        int newline = candidate.indexOf('\n');
        if (newline > 0) {
            candidate = candidate.substring(0, newline).strip();
        }
        if (candidate.length() > maximumCharacters) {
            candidate = candidate.substring(0, maximumCharacters).strip();
        }
        return candidate;
    }
}
