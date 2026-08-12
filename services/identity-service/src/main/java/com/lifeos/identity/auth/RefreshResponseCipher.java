package com.lifeos.identity.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/** Encrypts the bounded response envelope retained for one refresh retry. */
@Component
public class RefreshResponseCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates a cipher using the dedicated replay-envelope key.
     *
     * @param properties authentication properties
     * @param material resolved JWT and replay key material
     */
    public RefreshResponseCipher(IdentityAuthProperties properties, JwtSigningMaterial material) {
        this.key = material.replayEncryptionKey(properties);
    }

    /**
     * Encrypts a retry response and authenticates the replay-record identity as associated data.
     *
     * @param familyId token-family identifier
     * @param idempotencyKey client idempotency key
     * @param response response retained for one retry
     * @return nonce and ciphertext envelope
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public String encrypt(UUID familyId, String idempotencyKey, LoginResponse response) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(identity(familyId, idempotencyKey));
            byte[] ciphertext = cipher.doFinal(serialize(response).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (java.security.GeneralSecurityException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    /**
     * Decrypts a replay response after authenticating its record identity.
     *
     * @param familyId token-family identifier
     * @param idempotencyKey client idempotency key
     * @param envelope nonce and ciphertext envelope
     * @return retained response
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public LoginResponse decrypt(UUID familyId, String idempotencyKey, String envelope) {
        try {
            if (envelope == null || envelope.isBlank()) {
                throw new AuthenticationFailureException();
            }
            String[] parts = envelope.split("\\.", -1);
            if (parts.length != 2) {
                throw new AuthenticationFailureException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(
                    GCM_TAG_BITS, Base64.getUrlDecoder().decode(parts[0])));
            cipher.updateAAD(identity(familyId, idempotencyKey));
            return deserialize(new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[1])),
                    StandardCharsets.UTF_8));
        } catch (AuthenticationFailureException exception) {
            throw exception;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            // The cause is deliberately dropped. Decryption details must not reach the caller.
            throw new AuthenticationFailureException();
        }
    }

    private byte[] identity(UUID familyId, String idempotencyKey) {
        if (familyId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AuthenticationFailureException();
        }
        return (familyId + "|" + idempotencyKey).getBytes(StandardCharsets.UTF_8);
    }

    private String serialize(LoginResponse response) {
        return String.join("|",
                response.sessionId().toString(),
                response.accessToken(),
                response.tokenType(),
                Long.toString(response.expiresIn()),
                response.refreshToken(),
                Long.toString(response.refreshExpiresIn()));
    }

    private LoginResponse deserialize(String value) {
        String[] fields = value.split("\\|", -1);
        if (fields.length != 6 || fields[4].isBlank()) {
            throw new AuthenticationFailureException();
        }
        try {
            return new LoginResponse(
                    UUID.fromString(fields[0]),
                    fields[1],
                    fields[2],
                    Long.parseLong(fields[3]),
                    fields[4],
                    Long.parseLong(fields[5]));
        } catch (IllegalArgumentException exception) {
            // Malformed persisted envelope data is intentionally sanitized as an auth failure.
            throw new AuthenticationFailureException();
        }
    }
}
