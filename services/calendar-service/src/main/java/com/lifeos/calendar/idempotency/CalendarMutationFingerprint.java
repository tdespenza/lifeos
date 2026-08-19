package com.lifeos.calendar.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.calendar.config.CalendarProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC-based idempotency-key and canonical request fingerprints without retaining raw keys. */
@Component
public class CalendarMutationFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public CalendarMutationFingerprint(ObjectMapper objectMapper, CalendarProperties properties) {
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
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new CalendarIdempotencyUnavailableException(exception);
        }
    }
}
