package com.lifeos.identity.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Opaque, bounded identifier for one server-side WebAuthn assertion request.
 *
 * <p>The value is deliberately separate from the WebAuthn challenge embedded in the serialized
 * {@code AssertionRequest}. It is safe to return to the browser as a correlation handle, while
 * the complete protocol request remains server-side in Redis.
 */
public record WebAuthnChallengeId(String value) {

    private static final int RANDOM_BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    /**
     * Validates the serialized challenge-handle format.
     *
     * @param value opaque challenge handle
     */
    public WebAuthnChallengeId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("WebAuthn challenge id must be 43 URL-safe characters");
        }
    }

    /**
     * Generates a cryptographically random challenge handle.
     *
     * @param secureRandom cryptographically secure random source
     * @return valid opaque challenge handle
     */
    public static WebAuthnChallengeId generate(SecureRandom secureRandom) {
        if (secureRandom == null) {
            throw new IllegalArgumentException("secureRandom must not be null");
        }
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return new WebAuthnChallengeId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /**
     * Parses an untrusted client-supplied challenge handle without throwing for malformed input.
     *
     * @param value untrusted challenge handle
     * @return parsed handle, or empty when malformed
     */
    public static Optional<WebAuthnChallengeId> parse(String value) {
        return value == null || !FORMAT.matcher(value).matches()
                ? Optional.empty()
                : Optional.of(new WebAuthnChallengeId(value));
    }
}
