package com.lifeos.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way digest utility for bearer-token persistence.
 */
public final class TokenDigest {

    /**
     * Prevents instantiation of this stateless utility.
     */
    private TokenDigest() {
    }

    /**
     * Returns a SHA-256 digest suitable for a fixed-length database column.
     *
     * @param token raw token held only in memory during issuance
     * @return lower-case hexadecimal SHA-256 digest
     */
    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
