package com.lifeos.identity.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.identity.auth.IdentityAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** Proves that the configured V2 default starts while retaining the bounded V1 migration view. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("test")
class AuthorizationPolicyV2StartupIntegrationTest {

    @Autowired
    private IdentityAuthProperties properties;

    @Autowired
    private DefaultAuthorizationPolicyRepository policyRepository;

    @Test
    void startsWithV2AndRetainsExactV1CompatibilityForLegacyActionsOnly() {
        assertThat(properties.getAuthorization().getPolicyVersion()).isEqualTo("v2");
        assertThat(policyRepository.loadCurrentPolicy().version()).isEqualTo("v2");
        assertThat(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.GOAL_READ))
                .hasValueSatisfying(policy -> assertThat(policy.version()).isEqualTo("v1"));
        assertThat(policyRepository.findCompatiblePolicy("v1", AuthorizationAction.CALENDAR_EVENT_READ))
                .isEmpty();
        assertThat(properties.getAuthorization().workloadCredential(
                        AuthorizationActionDescriptorRegistry.DOCUMENT_VAULT_WORKLOAD))
                .isNotBlank();
        assertThat(properties.getAuthorization().workloadCredential(
                        AuthorizationActionDescriptorRegistry.MEDIA_WORKLOAD))
                .isNotBlank();
        assertThat(properties.getAuthorization().workloadCredential("ai-assistant-service"))
                .isEqualTo("test-only-ai-assistant-workload-secret");
    }
}
