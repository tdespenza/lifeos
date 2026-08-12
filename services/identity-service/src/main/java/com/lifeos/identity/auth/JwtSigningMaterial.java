package com.lifeos.identity.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
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
                if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                    throw new IllegalStateException(
                            "Configured JWT RSA private and public keys do not match");
                }
                Signature probe = Signature.getInstance("SHA256withRSA");
                byte[] probeMessage = "lifeos-jwt-key-pair-probe-v1"
                        .getBytes(StandardCharsets.US_ASCII);
                probe.initSign(privateKey);
                probe.update(probeMessage);
                byte[] signature = probe.sign();
                probe.initVerify(publicKey);
                probe.update(probeMessage);
                if (!probe.verify(signature)) {
                    throw new IllegalStateException(
                            "Configured JWT RSA private and public keys do not form a valid "
                                    + "signing pair");
                }
                if (publicKey.getModulus().bitLength() < 2048) {
                    throw new IllegalStateException("JWT RSA keys must be at least 2048 bits");
                }
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

    /**
     * Returns the resolved RSA key pair, when asymmetric signing is configured.
     *
     * @return RSA key, or {@code null} for HMAC signing
     */
    public RSAKey rsaKey() {
        return rsaKey;
    }

    /**
     * Returns the resolved HMAC key, when compatibility signing is configured.
     *
     * @return HMAC key, or {@code null} for RSA signing
     */
    public SecretKey hmacKey() {
        return hmacKey;
    }

    /**
     * Returns the public RSA key used for JWT verification.
     *
     * @return RSA public key, or {@code null} for HMAC signing
     */
    public RSAPublicKey rsaPublicKey() {
        return rsaPublicKey;
    }

    /**
     * Returns the configured key identifier emitted in JWT headers.
     *
     * @return signing key identifier
     */
    public String keyId() {
        return keyId;
    }

    /**
     * Creates the canonical JWT header for the resolved signing material.
     *
     * @return JWT header containing algorithm, key id, and type
     */
    public JwsHeader jwtHeader() {
        return (asymmetric
                ? JwsHeader.with(SignatureAlgorithm.RS256)
                : JwsHeader.with(MacAlgorithm.HS256))
                .keyId(keyId)
                .type("JWT")
                .build();
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
     * Derives the replay key for encrypting the one-retry response envelope using a public
     * domain-separation label and the dedicated secret.
     *
     * <p>The raw JWT or refresh token is never used as the key or persisted in plaintext. The
     * label is not key material; the configured secret remains externalized.
     *
     * @param properties authentication properties
     * @return 256-bit AES key
     */
    @SuppressWarnings("PMD.HardCodedCryptoKey")
    public SecretKey replayEncryptionKey(IdentityAuthProperties properties) {
        String configured = properties.getJwt().getReplayEncryptionSecret();
        if (!StringUtils.hasText(configured)
                || configured.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "Configure a dedicated replay encryption secret of at least 32 bytes");
        }
        String source = "lifeos-refresh-replay-envelope-v1|" + configured;
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
