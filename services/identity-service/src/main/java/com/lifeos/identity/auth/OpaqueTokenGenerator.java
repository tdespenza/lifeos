package com.lifeos.identity.auth;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Generates high-entropy opaque refresh credentials. */
@Component
public class OpaqueTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();

    /**
     * Generates one 256-bit URL-safe opaque credential.
     *
     * @return random refresh credential
     */
    public String next() {
        byte[] value = new byte[TOKEN_BYTES];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
