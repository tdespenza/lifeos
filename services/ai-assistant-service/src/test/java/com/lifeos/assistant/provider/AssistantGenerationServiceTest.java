package com.lifeos.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.assistant.config.AiAssistantProperties;
import com.lifeos.assistant.conversation.AssistantConversationPurpose;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Verifies the provider boundary cannot call an unconfigured model implementation. */
class AssistantGenerationServiceTest {

    @Test
    void refusesGenerationBeforeCallingAnUnconfiguredProvider() {
        AtomicBoolean called = new AtomicBoolean();
        AssistantProvider provider = new AssistantProvider() {
            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public String providerId() {
                return "test-disabled";
            }

            @Override
            public String modelName() {
                return "unconfigured";
            }

            @Override
            public AssistantProviderResponse generate(AssistantProviderRequest request) {
                called.set(true);
                return new AssistantProviderResponse("unexpected", "test-disabled", "unconfigured", null);
            }
        };
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AssistantGenerationService service = new AssistantGenerationService(provider, executor, properties());

            assertThatThrownBy(() -> service.generate(request())).isInstanceOf(AssistantProviderNotConfiguredException.class);
            assertThat(called.get()).isFalse();
        }
    }

    private static AiAssistantProperties properties() {
        AiAssistantProperties properties = new AiAssistantProperties();
        properties.setAuditHmacSecret("test-audit-secret");
        return properties;
    }

    private static AssistantProviderRequest request() {
        return new AssistantProviderRequest(
                "assistant-general-v1", AssistantConversationPurpose.GENERAL, "safe request", 32);
    }
}
