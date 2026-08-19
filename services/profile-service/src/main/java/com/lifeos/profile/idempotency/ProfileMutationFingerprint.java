package com.lifeos.profile.idempotency;

import com.lifeos.profile.config.ProfileServiceProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC fingerprints for durable retry comparison without persisting raw keys or request content. */
@Component
public class ProfileMutationFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public ProfileMutationFingerprint(ProfileServiceProperties properties) {
        key = new SecretKeySpec(properties.getIdempotencySecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String keyHash(String rawKey) {
        return hmac("key", rawKey);
    }

    /**
     * Delimited length-prefix encoding removes ambiguity between request fields before they are
     * keyed. Callers sort unordered values before passing them here.
     */
    public String requestFingerprint(String... values) {
        StringBuilder canonical = new StringBuilder();
        Arrays.stream(values).forEach(value -> {
            String safeValue = value == null ? "<null>" : value;
            canonical.append(safeValue.length()).append(':').append(safeValue).append('|');
        });
        return hmac("request", canonical.toString());
    }

    private String hmac(String domain, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] bytes = mac.doFinal((domain + ':' + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                encoded.append(Character.forDigit((valueByte >>> 4) & 0x0f, 16));
                encoded.append(Character.forDigit(valueByte & 0x0f, 16));
            }
            return encoded.toString();
        } catch (GeneralSecurityException exception) {
            throw new ProfileIdempotencyUnavailableException(exception);
        }
    }
}
