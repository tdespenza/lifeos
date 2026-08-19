package com.lifeos.finance.idempotency;

import com.lifeos.finance.config.FinanceServiceProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HMAC-SHA-256 fingerprints raw keys and canonical mutation values without persisting either. */
@Component
public class FinanceMutationFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public FinanceMutationFingerprint(FinanceServiceProperties properties) {
        key = new SecretKeySpec(properties.getIdempotencySecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String keyHash(String idempotencyKey) {
        return digest("key", idempotencyKey);
    }

    /** Length-prefixing avoids delimiter ambiguity between otherwise distinct request shapes. */
    public String fingerprint(String operation, String... values) {
        StringBuilder canonical = new StringBuilder(operation.length() + values.length * 16);
        append(canonical, operation);
        for (String value : values) {
            append(canonical, value);
        }
        return digest("request", canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "<null>" : value;
        target.append(safe.length()).append(':').append(safe);
    }

    private String digest(String domain, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            mac.update(domain.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                encoded.append(String.format("%02x", valueByte));
            }
            return encoded.toString();
        } catch (GeneralSecurityException exception) {
            throw new FinanceIdempotencyUnavailableException(exception);
        }
    }
}
