package com.lifeos.identity.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import java.util.List;

/**
 * Authentication infrastructure beans owned by the identity service.
 */
@Configuration
public class AuthConfiguration {

    /**
     * Creates the authentication bean configuration.
     */
    public AuthConfiguration() {
    }

    /**
     * Creates the explicitly configured Argon2id password encoder.
     *
     * @param properties authentication properties
     * @return configured password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder(IdentityAuthProperties properties) {
        IdentityAuthProperties.Password password = properties.getPassword();
        return new Argon2PasswordEncoder(
                16,
                32,
                password.getParallelism(),
                password.getMemoryKiB(),
                password.getIterations());
    }

    /**
     * Creates an HMAC JWT encoder from an externally supplied secret.
     *
     * <p>The secret is intentionally mandatory. A generated or committed fallback would produce
     * tokens that are inconsistent across instances or trivially forgeable.
     *
     * @param properties authentication properties
     * @return JWT encoder
     */
    @Bean
    public JwtSigningMaterial jwtSigningMaterial(IdentityAuthProperties properties) {
        return JwtSigningMaterial.from(properties);
    }

    /**
     * Creates the configured JWT encoder. RSA signing is preferred; HMAC remains available for
     * local compatibility profiles and is never published to verifiers.
     *
     * @param material immutable key material
     * @return JWT encoder
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtSigningMaterial material) {
        return new NimbusJwtEncoder(material.signingSource());
    }

    /**
     * Creates a decoder with issuer, audience, signature, and time-window validation.
     *
     * @param properties authentication properties
     * @param material immutable key material
     * @return JWT decoder
     */
    @Bean
    public JwtDecoder jwtDecoder(IdentityAuthProperties properties, JwtSigningMaterial material) {
        NimbusJwtDecoder decoder;
        if (material.isAsymmetric()) {
            decoder = NimbusJwtDecoder.withPublicKey(material.rsaPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } else {
            decoder = NimbusJwtDecoder.withSecretKey(material.hmacKey())
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        }
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(
                properties.getJwt().getIssuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(properties.getJwt().getAudience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }
}
