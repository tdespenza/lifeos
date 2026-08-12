package com.lifeos.identity.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for one-time refresh-token rotation. */
@RestController
public class RefreshController {

    private static final String REFRESH_COOKIE = "lifeos_refresh";
    private final RefreshTokenService refreshTokenService;

    public RefreshController(RefreshTokenService refreshTokenService) {
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody(required = false) RefreshRequestBody body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Client-Fingerprint", required = false) String clientFingerprint,
            HttpServletRequest request) {
        String refreshToken = body == null ? null : body.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = cookieValue(request, REFRESH_COOKIE);
        }
        String fingerprint = TokenDigest.sha256(
                clientFingerprint == null || clientFingerprint.isBlank() ? "default" : clientFingerprint);
        return ResponseEntity.ok(refreshTokenService.refresh(new RefreshTokenService.RefreshRequest(
                refreshToken, idempotencyKey, fingerprint)));
    }

    private String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record RefreshRequestBody(@NotBlank String refreshToken) {
    }
}
