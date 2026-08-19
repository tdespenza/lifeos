package com.lifeos.notification.security;

import com.lifeos.notification.config.NotificationProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-256-GCM endpoint cipher with a versioned ciphertext format and authenticated context. */
@Component
public class AesGcmEndpointCipher implements EndpointCipher {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] ASSOCIATED_DATA = "lifeos:notification-endpoint:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmEndpointCipher(NotificationProperties properties) {
        this.key = new SecretKeySpec(
                Base64.getDecoder().decode(properties.getEndpointEncryptionKey()), "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("endpoint destination must not be blank");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new EndpointCipherException("endpoint encryption failed", exception);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        try {
            String[] parts = ciphertext == null ? new String[0] : ciphertext.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new EndpointCipherException("unsupported endpoint ciphertext format");
            }
            byte[] iv = decodeCanonical(parts[1]);
            if (iv.length != IV_BYTES) {
                throw new EndpointCipherException("invalid endpoint ciphertext IV length");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(ASSOCIATED_DATA);
            return new String(cipher.doFinal(decodeCanonical(parts[2])), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new EndpointCipherException("endpoint decryption failed", exception);
        }
    }

    private static byte[] decodeCanonical(String encoded) {
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (!canonical.equals(encoded)) {
            throw new EndpointCipherException("non-canonical endpoint ciphertext encoding");
        }
        return decoded;
    }
}
