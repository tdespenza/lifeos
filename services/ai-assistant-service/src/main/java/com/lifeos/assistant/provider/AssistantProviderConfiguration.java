package com.lifeos.assistant.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.config.AiAssistantProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Supplies a deliberately inert provider and a bounded-lifetime virtual-thread executor. */
@Configuration
public class AssistantProviderConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "ai-assistant.provider", name = "mode", havingValue = "DISABLED", matchIfMissing = true)
    @ConditionalOnMissingBean(AssistantProvider.class)
    public AssistantProvider disabledAssistantProvider() {
        return new DisabledAssistantProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai-assistant.provider", name = "mode", havingValue = "LOCAL_DETERMINISTIC")
    @ConditionalOnMissingBean(AssistantProvider.class)
    public AssistantProvider deterministicAssistantProvider(AiAssistantProperties properties) {
        return new DeterministicAssistantProvider(properties.getProvider());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai-assistant.provider", name = "mode", havingValue = "OPENAI_COMPATIBLE")
    @ConditionalOnMissingBean(AssistantProvider.class)
    public AssistantProvider openAiCompatibleAssistantProvider(
            RestClient.Builder builder, ObjectMapper objectMapper, AiAssistantProperties properties) {
        Duration timeout = properties.getProviderTimeout();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient client = builder.baseUrl(properties.getProvider().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new OpenAiCompatibleAssistantProvider(client, objectMapper, properties.getProvider());
    }

    @Bean(destroyMethod = "close")
    public ExecutorService assistantProviderExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    private static final class DisabledAssistantProvider implements AssistantProvider {

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public String providerId() {
            return "disabled";
        }

        @Override
        public String modelName() {
            return "unconfigured";
        }

        @Override
        public AssistantProviderResponse generate(AssistantProviderRequest request) {
            throw new AssistantProviderNotConfiguredException();
        }
    }
}
