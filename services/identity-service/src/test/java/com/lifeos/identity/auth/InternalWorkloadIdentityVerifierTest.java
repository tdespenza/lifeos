package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Verifies the fail-closed workload authentication required by internal HTTP adapters. */
class InternalWorkloadIdentityVerifierTest {

    private InternalWorkloadIdentityVerifier verifier;

    @BeforeEach
    void setUp() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getAuthorization().setWorkloadIdentities(Map.of(
                "ai-assistant-service", "test-ai-assistant-workload-credential"));
        verifier = new InternalWorkloadIdentityVerifier(properties);
    }

    @Test
    void acceptsConfiguredAiAssistantWorkloadWithMatchingCredential() {
        MockHttpServletRequest request = request("ai-assistant-service", "test-ai-assistant-workload-credential");

        assertThatCode(() -> verifier.verify(request)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingUnknownAndMismatchedAiAssistantCredentialsWithOneFailureType() {
        assertThatThrownBy(() -> verifier.verify(request("ai-assistant-service", null)))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(request("unknown-service", "test-ai-assistant-workload-credential")))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(request("ai-assistant-service", "wrong-credential")))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
    }

    @Test
    void rejectsAiAssistantIdentityWhenDeploymentOmittedItsCredential() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getAuthorization().setWorkloadIdentities(Map.of("ai-assistant-service", ""));

        assertThatThrownBy(() -> new InternalWorkloadIdentityVerifier(properties)
                .verify(request("ai-assistant-service", "anything")))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
    }

    private MockHttpServletRequest request(String identity, String credential) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (identity != null) {
            request.addHeader(InternalWorkloadIdentityVerifier.IDENTITY_HEADER, identity);
        }
        if (credential != null) {
            request.addHeader(InternalWorkloadIdentityVerifier.TOKEN_HEADER, credential);
        }
        return request;
    }
}
