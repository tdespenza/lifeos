package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies that refresh credentials are sent only to the rotation endpoint. */
class RefreshCookieSupportTest {

    @Test
    void scopesCookieToRefreshEndpoint() {
        LoginResponse response = new LoginResponse(
                UUID.randomUUID(), "access", "Bearer", 300, "refresh", 3600);

        assertThat(RefreshCookieSupport.from(response).toString())
                .contains("Path=/api/v1/auth/refresh")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
    }
}
