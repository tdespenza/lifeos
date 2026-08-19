package com.lifeos.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.conversation.AssistantConversationPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Contract tests for the bounded OpenAI-compatible provider adapter. */
class OpenAiCompatibleAssistantProviderTest {

    private MockRestServiceServer server;
    private AiAssistantProperties.Provider properties;
    private OpenAiCompatibleAssistantProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://provider.test/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new AiAssistantProperties.Provider();
        properties.setMode(AiAssistantProperties.ProviderMode.OPENAI_COMPATIBLE);
        properties.setBaseUrl("https://provider.test/v1");
        properties.setApiKey("provider-test-key");
        properties.setModel("test-model");
        provider = new OpenAiCompatibleAssistantProvider(builder.build(), new ObjectMapper(), properties);
    }

    @Test
    void sendsOnlyTheRedactedPromptAndParsesTheFirstChoice() {
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer provider-test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.messages[0].content").value("assistant-general-v1"))
                .andExpect(jsonPath("$.messages[1].content").value("[REDACTED_EMAIL]"))
                .andExpect(jsonPath("$.max_tokens").value(32))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"safe answer\"}}]}",
                        MediaType.APPLICATION_JSON));

        AssistantProviderResponse response = provider.generate(new AssistantProviderRequest(
                "assistant-general-v1", AssistantConversationPurpose.GENERAL, "[REDACTED_EMAIL]", 32));

        assertThat(response.text()).isEqualTo("safe answer");
        assertThat(response.providerId()).isEqualTo("openai-compatible");
        assertThat(response.modelName()).isEqualTo("test-model");
        server.verify();
    }

    @Test
    void rejectsResponsesThatExceedTheConfiguredByteBound() {
        properties.setMaxResponseBytes(32);
        String oversized = "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(64) + "\"}}]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(oversized, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AssistantProviderFailureException.class);
        server.verify();
    }

    @Test
    void remainsInertWhenProviderModeIsDisabled() {
        properties.setMode(AiAssistantProperties.ProviderMode.DISABLED);

        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AssistantProviderNotConfiguredException.class);
    }

    @Test
    void providerValidationRequiresHttpsOutsideLoopback() {
        properties.setBaseUrl("http://provider.test/v1");
        assertThat(properties.isValidWhenEnabled()).isFalse();

        properties.setBaseUrl("https://provider.test/v1");
        properties.setCompletionPath("/v1/../chat");
        assertThat(properties.isValidWhenEnabled()).isFalse();
    }

    private static AssistantProviderRequest request() {
        return new AssistantProviderRequest(
                "assistant-general-v1", AssistantConversationPurpose.GENERAL, "safe request", 32);
    }
}
