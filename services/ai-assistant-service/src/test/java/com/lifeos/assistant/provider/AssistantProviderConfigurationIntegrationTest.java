package com.lifeos.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Proves the opt-in provider mode replaces the safe disabled provider at application startup. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-provider-config;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=assistant-provider-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "ai-assistant.audit-hmac-secret=assistant-provider-audit-secret",
    "identity.workload-token=assistant-provider-workload-token",
    "ai-assistant.provider.mode=OPENAI_COMPATIBLE",
    "ai-assistant.provider.base-url=https://provider.test/v1",
    "ai-assistant.provider.model=test-model"
})
class AssistantProviderConfigurationIntegrationTest {

    @Autowired
    private AssistantProvider provider;

    @Test
    void createsTheConfiguredAdapterOnlyWhenExplicitlyEnabled() {
        assertThat(provider).isInstanceOf(OpenAiCompatibleAssistantProvider.class);
        assertThat(provider.isConfigured()).isTrue();
        assertThat(provider.providerId()).isEqualTo("openai-compatible");
        assertThat(provider.modelName()).isEqualTo("test-model");
    }
}
