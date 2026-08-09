package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies that invalid authentication configuration fails before the service can start.
 */
class IdentityAuthPropertiesTest {

    @Test
    void rejectsNonPositiveSessionSettings() {
        IdentityAuthProperties properties = new IdentityAuthProperties();

        assertThatThrownBy(() -> properties.setAccessTokenTtl(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accessTokenTtl must be positive");
        assertThatThrownBy(() -> properties.setMaxSessionsPerAccount(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSessionsPerAccount must be positive");
    }
}
