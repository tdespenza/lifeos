package com.lifeos.identity.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

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
    public JwtEncoder jwtEncoder(IdentityAuthProperties properties) {
        String secret = properties.getJwt().getSigningSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "IDENTITY_JWT_SIGNING_SECRET must contain at least 32 bytes for login to start");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(key)
                .algorithm(JWSAlgorithm.HS256)
                .build();
        JWKSet keySet = new JWKSet(jwk);
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(keySet);
        return new NimbusJwtEncoder(source);
    }
}
