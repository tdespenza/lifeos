package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the keyed redaction primitive used by Redis and security auditing.
 */
class HmacSha256DigestTest {

    private static final String KEY = "test-only-hmac-key-that-is-at-least-32-bytes-long";

    @Test
    void producesStableHexadecimalDigestWithoutEmbeddingInput() {
        HmacSha256Digest digest = new HmacSha256Digest(KEY, "TEST_KEY");

        String result = digest.digest("203.0.113.42");

        assertThat(result).hasSize(64).matches("[0-9a-f]+").doesNotContain("203.0.113.42");
        assertThat(digest.digest("203.0.113.42")).isEqualTo(result);
    }

    @Test
    void rejectsMissingOrWeakKeyMaterial() {
        assertThatThrownBy(() -> new HmacSha256Digest("too-short", "TEST_KEY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TEST_KEY must contain at least 32 bytes");
    }
}
