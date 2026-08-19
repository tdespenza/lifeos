package com.lifeos.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.conversation.AssistantConversationPurpose;
import org.junit.jupiter.api.Test;

class DeterministicAssistantProviderTest {

    @Test
    void returnsAStableBoundedExcerptWithoutNetworkAccess() {
        AiAssistantProperties.Provider properties = new AiAssistantProperties.Provider();
        properties.setMode(AiAssistantProperties.ProviderMode.LOCAL_DETERMINISTIC);
        DeterministicAssistantProvider provider = new DeterministicAssistantProvider(properties);

        AssistantProviderResponse response = provider.generate(new AssistantProviderRequest(
                "assistant-grounded-document-answer-v1",
                AssistantConversationPurpose.GENERAL,
                "Question: What is the retention policy?\nEvidence: [source=secret] private context",
                32));

        assertThat(response.text()).isEqualTo("Local deterministic evidence answer: What is the retention policy?");
        assertThat(response.text()).hasSizeLessThanOrEqualTo(128);
        assertThat(response.providerId()).isEqualTo("local-deterministic");
        assertThat(response.modelName()).isEqualTo("rules-v1");
        assertThat(response.confidenceScore()).isEqualByComparingTo("1");
    }

    @Test
    void remainsInertOutsideItsExplicitMode() {
        AiAssistantProperties.Provider properties = new AiAssistantProperties.Provider();
        DeterministicAssistantProvider provider = new DeterministicAssistantProvider(properties);

        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(() -> provider.generate(new AssistantProviderRequest(
                        "assistant-general-v1", AssistantConversationPurpose.GENERAL, "safe", 32)))
                .isInstanceOf(AssistantProviderNotConfiguredException.class);
    }
}
