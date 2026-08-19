package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies that invalid authentication configuration fails before the service can start.
 */
class IdentityAuthPropertiesTest {

    @Test
    void rejectsNonPositiveSessionSettings() {
        IdentityAuthProperties properties = new IdentityAuthProperties();

        assertThatThrownBy(() -> properties.setAccessTokenTtl(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accessTokenTtl must be positive");
        assertThatThrownBy(() -> properties.setMaxSessionsPerAccount(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSessionsPerAccount must be positive");
    }

    @Test
    void rejectsInvalidPasswordAndJwtSettings() {
        IdentityAuthProperties properties = new IdentityAuthProperties();

        assertThatThrownBy(() -> properties.getPassword().setMemoryKiB(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memoryKiB must be positive");
        assertThatThrownBy(() -> properties.getPassword().setVerificationAcquireTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("verificationAcquireTimeout must be positive");
        assertThatThrownBy(() -> properties.getRegistration().setIdempotencySecret("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencySecret must contain at least 32 bytes");
        assertThatThrownBy(() -> properties.getJwt().setIssuer(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("issuer must not be blank");
        assertThatThrownBy(() -> properties.getJwt().setSigningSecret("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("signingSecret must contain at least 32 bytes");
        assertThatThrownBy(() -> properties.getJwt().setReplayEncryptionSecret("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("replayEncryptionSecret must contain at least 32 bytes");
    }

    @Test
    void rejectsBlankOrIncompleteOidcScope() {
        IdentityAuthProperties.Provider provider = new IdentityAuthProperties.Provider();

        assertThatThrownBy(() -> provider.setScope(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scope must be non-blank and include openid");
        assertThatThrownBy(() -> provider.setScope("profile email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scope must be non-blank and include openid");
    }

    @Test
    void acceptsOnlyPolicyVersionsImplementedByTheAuthorizationAuthority() {
        IdentityAuthProperties.Authorization authorization = new IdentityAuthProperties.Authorization();

        assertThat(authorization.getPolicyVersion()).isEqualTo("v2");
        authorization.setPolicyVersion("v1");
        assertThat(authorization.getPolicyVersion()).isEqualTo("v1");
        authorization.setPolicyVersion("v2");
        assertThat(authorization.getPolicyVersion()).isEqualTo("v2");
        assertThatThrownBy(() -> authorization.setPolicyVersion("v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("policyVersion is not implemented by this authorization authority");
    }
}
