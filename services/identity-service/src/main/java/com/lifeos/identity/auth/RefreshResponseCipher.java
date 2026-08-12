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

    public RefreshResponseCipher(IdentityAuthProperties properties, JwtSigningMaterial material) {
        this.key = material.replayEncryptionKey(properties);
    }

    public String encrypt(LoginResponse response) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(serialize(response).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (java.security.GeneralSecurityException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    public LoginResponse decrypt(String envelope) {
        try {
            String[] parts = envelope.split("\\.", -1);
            if (parts.length != 2) {
                throw new AuthenticationFailureException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(
                    GCM_TAG_BITS, Base64.getUrlDecoder().decode(parts[0])));
            return deserialize(new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[1])),
                    StandardCharsets.UTF_8));
        } catch (AuthenticationFailureException exception) {
            throw exception;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new AuthenticationFailureException();
        }
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
            throw new AuthenticationFailureException();
        }
    }
}
