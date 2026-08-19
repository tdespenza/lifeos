package com.lifeos.analytics.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Verifies the gateway's bounded, non-bearer transport proof before exposing analytics data. */
@Component
public class GatewayProofVerifier {

    public static final String ACCOUNT_HEADER = "X-LifeOS-Authenticated-Account-Id";
    public static final String SESSION_HEADER = "X-LifeOS-Authenticated-Session-Id";
    public static final String PROOF_HEADER = "X-LifeOS-Gateway-Proof";

    private final AnalyticsProperties properties;

    public GatewayProofVerifier(AnalyticsProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String method, String path, String accountId, String sessionId, String proof) {
        if (isBlank(method) || isBlank(path) || isBlank(accountId) || isBlank(sessionId) || isBlank(proof)) {
            return false;
        }
        String payload = method + "\n" + path + "\n" + accountId + "\n" + sessionId;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getGatewayProofSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(proof);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
