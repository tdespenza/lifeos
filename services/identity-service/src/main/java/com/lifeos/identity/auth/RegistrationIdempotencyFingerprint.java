package com.lifeos.identity.auth;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Produces HMAC-protected, domain-separated registration idempotency fingerprints.
 *
 * <p>The request fingerprint includes the transient raw password so the same key cannot be
 * replayed with a different password. It is never logged or persisted directly: only a keyed
 * HMAC digest reaches the database.
 */
@Component
public class RegistrationIdempotencyFingerprint {

    private static final String KEY_DOMAIN = "lifeos:account-registration:idempotency-key:v1";
    private static final String REQUEST_DOMAIN = "lifeos:account-registration:request:v1";

    private final HmacSha256Digest digest;

    /**
     * Creates the fingerprint service from a dedicated deployment secret.
     *
     * @param properties externally configured authentication settings
     */
    public RegistrationIdempotencyFingerprint(IdentityAuthProperties properties) {
        this.digest = new HmacSha256Digest(
                properties.getRegistration().getIdempotencySecret(),
                "IDENTITY_REGISTRATION_IDEMPOTENCY_SECRET");
    }

    /**
     * Returns a safe representation of one opaque client idempotency key.
     *
     * @param idempotencyKey validated client key
     * @return lower-case HMAC-SHA-256 digest
     */
    public String keyHash(String idempotencyKey) {
        return digest.digest(framed(KEY_DOMAIN, idempotencyKey));
    }

    /**
     * Returns a safe representation of one canonical registration payload.
     *
     * @param normalizedEmail canonical account email
     * @param displayName submitted display name
     * @param rawPassword transient validated password
     * @return lower-case HMAC-SHA-256 digest
     */
    public String requestFingerprint(String normalizedEmail, String displayName, String rawPassword) {
        return digest.digest(framed(REQUEST_DOMAIN, normalizedEmail, displayName, rawPassword));
    }

    private static String framed(String domain, String... values) {
        StringBuilder canonical = new StringBuilder(domain).append('\u0000');
        for (String value : values) {
            String required = Objects.requireNonNull(value, "registration fingerprint value must not be null");
            canonical.append(required.length()).append(':').append(required).append('\u0000');
        }
        return canonical.toString();
    }
}
