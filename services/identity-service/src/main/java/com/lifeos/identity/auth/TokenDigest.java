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

    /**
     * Compares two fixed-format token proofs without stopping at the first differing byte.
     *
     * <p>The values are never logged or returned to a public client. This method lets an internal
     * authorization decision bind to the exact bearer token that validation accepted, even when a
     * concurrent refresh rotates the durable token hash.
     *
     * @param expected persisted token proof
     * @param actual proof received from the authenticated validation bridge
     * @return {@code true} only when both values are well-formed and identical
     */
    public static boolean matches(String expected, String actual) {
        if (!isSha256Hex(expected) || !isSha256Hex(actual)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Returns whether a value has the fixed representation used for a token proof.
     *
     * @param value candidate proof
     * @return {@code true} for a 64-character lower-case hexadecimal value
     */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
