package com.lifeos.identity.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/** Builds the browser refresh cookie with an explicit CSRF-reducing SameSite policy. */
final class RefreshCookieSupport {

    static final String NAME = "lifeos_refresh";
    static final String PATH = "/api/v1/auth";

    private RefreshCookieSupport() {
    }

    /**
     * Creates a host-only, HttpOnly, secure refresh cookie.
     *
     * @param response issued login or refresh response
     * @return cookie header value, or {@code null} for compatibility responses without refresh data
     */
    static ResponseCookie from(LoginResponse response) {
        if (response == null || response.refreshToken() == null || response.refreshToken().isBlank()) {
            return null;
        }
        return ResponseCookie.from(NAME, response.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(Duration.ofSeconds(response.refreshExpiresIn()))
                .build();
    }
}
