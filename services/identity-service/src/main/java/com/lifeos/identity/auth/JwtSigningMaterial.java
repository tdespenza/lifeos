package com.lifeos.identity.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

/**
 * Immutable signing-key material shared by the issuer, decoder, and JWKS endpoint.
 *
 * <p>Deployments should configure the RSA path. The HMAC path is retained only for the existing
 * local/test profile and deliberately never publishes its secret as a JWKS key.
 */
public final class JwtSigningMaterial {

    private final boolean asymmetric;
    private final RSAKey rsaKey;
    private final RSAPublicKey rsaPublicKey;
    private final SecretKey hmacKey;
    private final String keyId;

    private JwtSigningMaterial(
            boolean asymmetric,
            RSAKey rsaKey,
            RSAPublicKey rsaPublicKey,
            SecretKey hmacKey,
            String keyId) {
        this.asymmetric = asymmetric;
        this.rsaKey = rsaKey;
        this.rsaPublicKey = rsaPublicKey;
        this.hmacKey = hmacKey;
        this.keyId = keyId;
    }

    /**
     * Builds signing material from externalized configuration.
     *
     * @param properties authentication properties
     * @return immutable signing material
     */
    public static JwtSigningMaterial from(IdentityAuthProperties properties) {
        IdentityAuthProperties.Jwt jwt = properties.getJwt();
        String privatePem = jwt.getPrivateKeyPem();
        String publicPem = jwt.getPublicKeyPem();
        if (StringUtils.hasText(privatePem) || StringUtils.hasText(publicPem)) {
            if (!StringUtils.hasText(privatePem) || !StringUtils.hasText(publicPem)) {
                throw new IllegalStateException("Both JWT RSA private and public keys are required");
            }
            try {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(decodePem(privatePem, "PRIVATE KEY")));
                RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                        new X509EncodedKeySpec(decodePem(publicPem, "PUBLIC KEY")));
                RSAKey key = new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(jwt.getSigningKeyId())
                        .algorithm(JWSAlgorithm.RS256)
                        .build();
                return new JwtSigningMaterial(true, key, publicKey, null, jwt.getSigningKeyId());
            } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
                throw new IllegalStateException("Configured JWT RSA key material is invalid", exception);
            }
        }
        String secret = jwt.getSigningSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "Configure JWT RSA PEM keys or an HMAC secret of at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new JwtSigningMaterial(false, null, null, key, jwt.getSigningKeyId());
    }

    /**
     * Returns whether this material uses asymmetric signing.
     *
     * @return true for RSA
     */
    public boolean isAsymmetric() {
        return asymmetric;
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    public SecretKey hmacKey() {
        return hmacKey;
    }

    public RSAPublicKey rsaPublicKey() {
        return rsaPublicKey;
    }

    public String keyId() {
        return keyId;
    }

    /**
     * Returns the public JWKS document. HMAC keys intentionally produce an empty set.
     *
     * @return public key set
     */
    public JWKSet publicJwkSet() {
        return asymmetric ? new JWKSet(rsaKey.toPublicJWK()) : new JWKSet();
    }

    /**
     * Returns the key source used by NimbusJwtEncoder.
     *
     * @return key source
     */
    public JWKSource<SecurityContext> signingSource() {
        JWKSet keySet = asymmetric ? new JWKSet(rsaKey) : new JWKSet(
                new OctetSequenceKey.Builder(hmacKey)
                        .algorithm(JWSAlgorithm.HS256)
                        .keyID(keyId)
                        .build());
        return (selector, context) -> selector.select(keySet);
    }

    /**
     * Derives a stable AES key for encrypting the one-retry response envelope. The raw JWT or
     * refresh token is never used as the key or persisted in plaintext.
     *
     * @param properties authentication properties
     * @return 256-bit AES key
     */
    public SecretKey replayEncryptionKey(IdentityAuthProperties properties) {
        String configured = properties.getJwt().getReplayEncryptionSecret();
        String source = StringUtils.hasText(configured)
                ? configured
                : (StringUtils.hasText(properties.getJwt().getSigningSecret())
                        ? properties.getJwt().getSigningSecret()
                        : rsaKey.getModulus().toString() + rsaKey.getPrivateExponent().toString());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the platform", exception);
        }
    }

    private static byte[] decodePem(String pem, String label) {
        String normalized = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
