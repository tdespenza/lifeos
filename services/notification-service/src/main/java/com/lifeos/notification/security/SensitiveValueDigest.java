package com.lifeos.notification.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** SHA-256 helper for idempotency and diagnostics; raw sensitive values are never persisted as keys. */
public final class SensitiveValueDigest {

    private SensitiveValueDigest() {
    }

    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder rendered = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                rendered.append(String.format("%02x", part));
            }
            return rendered.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be provided by the Java runtime", exception);
        }
    }

    /** Domain-separated HMAC digest for low-entropy opaque values such as contact destinations. */
    public static String hmacSha256(String secret, String domain, String value) {
        if (secret == null || domain == null || value == null) {
            throw new IllegalArgumentException("secret, domain, and value must not be null");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal((domain + "|" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 must be provided by the Java runtime", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder rendered = new StringBuilder(bytes.length * 2);
        for (byte part : bytes) {
            rendered.append(String.format("%02x", part));
        }
        return rendered.toString();
    }
}
