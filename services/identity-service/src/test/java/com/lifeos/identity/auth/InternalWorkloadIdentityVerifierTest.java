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
                "task-goal-service", "test-workload-credential"));
        verifier = new InternalWorkloadIdentityVerifier(properties);
    }

    @Test
    void acceptsConfiguredWorkloadWithMatchingCredential() {
        MockHttpServletRequest request = request("task-goal-service", "test-workload-credential");

        assertThatCode(() -> verifier.verify(request)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingUnknownAndMismatchedWorkloadsWithOneFailureType() {
        assertThatThrownBy(() -> verifier.verify(request(null, null)))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(request("unknown-service", "test-workload-credential")))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
        assertThatThrownBy(() -> verifier.verify(request("task-goal-service", "wrong-credential")))
                .isInstanceOf(InternalWorkloadAuthenticationException.class);
    }

    @Test
    void rejectsConfiguredIdentityWhenDeploymentOmittedItsCredential() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getAuthorization().setWorkloadIdentities(Map.of("task-goal-service", ""));

        assertThatThrownBy(() -> new InternalWorkloadIdentityVerifier(properties)
                .verify(request("task-goal-service", "anything")))
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
