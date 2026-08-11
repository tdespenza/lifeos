package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that deployment cannot widen WebAuthn browser-origin trust accidentally.
 */
class WebAuthnConfigurationTest {

    private final WebAuthnConfiguration configuration = new WebAuthnConfiguration();

    @Test
    void acceptsExactHttpsOrigin() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getWebauthn().setAllowedOrigins(Set.of("https://app.example.com"));

        assertThat(configuration.relyingParty(
                properties, mock(WebAuthnCredentialRepositoryAdapter.class))).isNotNull();
    }

    @Test
    void rejectsNonLocalHttpOrigin() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getWebauthn().setAllowedOrigins(Set.of("http://app.example.com"));

        assertThatThrownBy(() -> configuration.relyingParty(
                properties, mock(WebAuthnCredentialRepositoryAdapter.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_WEBAUTHN_ALLOWED_ORIGINS");
    }

    @Test
    void rejectsOriginWithPath() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getWebauthn().setAllowedOrigins(Set.of("https://app.example.com/login"));

        assertThatThrownBy(() -> configuration.relyingParty(
                properties, mock(WebAuthnCredentialRepositoryAdapter.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_WEBAUTHN_ALLOWED_ORIGINS");
    }
}
