package com.lifeos.identity.auth;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes domain-specific HMAC-SHA-256 digests without exposing the configured key.
 */
final class HmacSha256Digest {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_KEY_BYTES = 32;

    private final SecretKeySpec key;

    /**
     * Creates a keyed digest from an externally supplied secret.
     *
     * @param secret secret-manager-backed key material
     * @param settingName configuration setting name used only in the startup error
     */
    HmacSha256Digest(String secret, String settingName) {
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(settingName + " must contain at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * Computes the lower-case hexadecimal HMAC for one request value.
     *
     * @param value value held only for the duration of the digest operation
     * @return keyed lower-case hexadecimal digest
     */
    String digest(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is required by the runtime", exception);
        }
    }
}
