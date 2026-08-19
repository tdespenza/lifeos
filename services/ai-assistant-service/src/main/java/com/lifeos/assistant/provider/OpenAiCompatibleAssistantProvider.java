package com.lifeos.assistant.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.config.AiAssistantProperties.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Bounded adapter for OpenAI-compatible chat-completion endpoints, including local Ollama-style
 * deployments. The provider receives only the already-redacted prompt and its response is never
 * persisted by the assistant service.
 */
public final class OpenAiCompatibleAssistantProvider implements AssistantProvider {

    private static final String PROVIDER_ID = "openai-compatible";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Provider properties;

    public OpenAiCompatibleAssistantProvider(RestClient client, ObjectMapper objectMapper, Provider properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.getMode() == AiAssistantProperties.ProviderMode.OPENAI_COMPATIBLE;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String modelName() {
        return properties.getModel();
    }

    @Override
    public AssistantProviderResponse generate(AssistantProviderRequest request) {
        if (!isConfigured()) {
            throw new AssistantProviderNotConfiguredException();
        }
        try {
            String apiKey = properties.getApiKey();
            CompletionResponse response = client.post()
                    .uri(properties.getCompletionPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> addAuthorization(headers, apiKey))
                    .body(Map.of(
                            "model", properties.getModel(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", request.promptTemplateId()),
                                    Map.of("role", "user", "content", request.redactedPrompt())),
                            "max_tokens", request.maxOutputTokens(),
                            "temperature", 0))
                    .exchange((httpRequest, httpResponse) -> parseBounded(httpResponse));
            String text = response == null || response.choices() == null || response.choices().isEmpty()
                    ? null
                    : response.choices().getFirst().message() == null
                            ? null
                            : response.choices().getFirst().message().content();
            if (!StringUtils.hasText(text)) {
                throw new AssistantProviderFailureException(null);
            }
            return new AssistantProviderResponse(text.strip(), providerId(), modelName(), null);
        } catch (AssistantProviderFailureException | AssistantProviderNotConfiguredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Provider response bodies and transport messages can contain private content; callers
            // receive only the stable provider-failure classification.
            throw new AssistantProviderFailureException(null);
        }
    }

    private CompletionResponse parseBounded(ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssistantProviderFailureException(null);
        }
        byte[] bytes = readBounded(response.getBody(), properties.getMaxResponseBytes());
        try {
            return objectMapper.readValue(bytes, CompletionResponse.class);
        } catch (IOException | RuntimeException exception) {
            throw new AssistantProviderFailureException(null);
        }
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new AssistantProviderFailureException(null);
        }
        return bytes;
    }

    private static void addAuthorization(HttpHeaders headers, String apiKey) {
        if (StringUtils.hasText(apiKey)) {
            headers.setBearerAuth(apiKey);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
}
