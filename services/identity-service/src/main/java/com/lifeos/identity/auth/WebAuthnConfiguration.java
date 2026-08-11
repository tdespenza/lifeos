package com.lifeos.identity.auth;

import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.PublicKeyCredential;
import java.net.URI;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the immutable WebAuthn relying-party validator from explicit deployment configuration.
 */
@Configuration
public class WebAuthnConfiguration {

    /**
     * Creates the protocol-library assertion parser behind a small application boundary so the
     * authentication orchestration remains unit-testable without static parsing calls.
     *
     * @return typed assertion parser
     */
    @Bean
    public WebAuthnAssertionParser webAuthnAssertionParser() {
        return PublicKeyCredential::parseAssertionResponseJson;
    }

    /**
     * Creates the relying-party validator used for every passkey ceremony.
     *
     * @param properties identity authentication properties
     * @param credentialRepository database adapter for registered credentials
     * @return immutable relying-party validator
     */
    @Bean
    public RelyingParty relyingParty(
            IdentityAuthProperties properties,
            WebAuthnCredentialRepositoryAdapter credentialRepository) {
        IdentityAuthProperties.WebAuthn webAuthn = properties.getWebauthn();
        validateOrigins(webAuthn.getAllowedOrigins());
        RelyingParty.RelyingPartyBuilder builder = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id(webAuthn.getRpId())
                        .name(webAuthn.getRpName())
                        .build())
                .credentialRepository(credentialRepository);
        return builder
                .origins(webAuthn.getAllowedOrigins())
                .validateSignatureCounter(true)
                .build();
    }

    private void validateOrigins(Set<String> origins) {
        origins.forEach(origin -> {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("IDENTITY_WEBAUTHN_ALLOWED_ORIGINS contains an invalid origin", exception);
            }
            boolean secureOrigin = "https".equalsIgnoreCase(uri.getScheme());
            boolean localHttpOrigin = "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                            || "127.0.0.1".equals(uri.getHost()));
            if ((!secureOrigin && !localHttpOrigin)
                    || uri.getHost() == null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalStateException(
                        "IDENTITY_WEBAUTHN_ALLOWED_ORIGINS must contain exact HTTPS origins; HTTP is allowed only for localhost");
            }
        });
    }
}
