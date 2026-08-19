package com.lifeos.assistant.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Proves the deterministic provider is opt-in and wired without a network dependency. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-deterministic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=assistant-deterministic-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "ai-assistant.audit-hmac-secret=assistant-deterministic-audit-secret",
    "identity.workload-token=assistant-deterministic-workload-token",
    "ai-assistant.provider.mode=LOCAL_DETERMINISTIC"
})
class DeterministicProviderConfigurationIntegrationTest {

    @Autowired
    private AssistantProvider provider;

    @Test
    void createsTheLocalProviderWithoutAnExternalEndpoint() {
        assertThat(provider).isInstanceOf(DeterministicAssistantProvider.class);
        assertThat(provider.isConfigured()).isTrue();
    }
}
