package com.lifeos.media.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.media.config.MediaProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC fingerprints avoid retaining raw idempotency keys and command input. */
@Component
public class MediaMutationFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public MediaMutationFingerprint(ObjectMapper objectMapper, MediaProperties properties) {
        this.objectMapper = objectMapper;
        secret = properties.getIdempotencySecret().getBytes(StandardCharsets.UTF_8);
    }

    public String idempotencyKeyHash(String value) {
        return digest("key|" + value);
    }

    public String requestHash(Object value) {
        try {
            return digest("request|" + objectMapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new MediaIdempotencyUnavailableException(exception);
        }
    }
}
