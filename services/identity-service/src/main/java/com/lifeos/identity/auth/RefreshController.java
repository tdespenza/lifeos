package com.lifeos.identity.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** REST boundary for one-time refresh-token rotation. */
@RestController
public class RefreshController {

    private final RefreshTokenService refreshTokenService;
    private final ClientAddressResolver clientAddressResolver;

    /**
     * Creates the refresh-token rotation boundary.
     *
     * @param refreshTokenService rotation authority
     * @param clientAddressResolver trusted client-address resolver
     */
    public RefreshController(
            RefreshTokenService refreshTokenService,
            ClientAddressResolver clientAddressResolver) {
        this.refreshTokenService = refreshTokenService;
        this.clientAddressResolver = clientAddressResolver;
    }

    /**
     * Rotates a JSON or cookie-sourced refresh credential.
     *
     * @param body optional JSON body
     * @param idempotencyKey client retry key
     * @param request servlet request used for cookie and server-observed fingerprint data
     * @return rotated access and refresh response
     */
    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @RequestBody(required = false) RefreshRequestBody body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String refreshToken = body == null ? null : body.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = cookieValue(request, RefreshCookieSupport.NAME);
        }
        String address = clientAddressResolver.resolve(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (address == null || address.isBlank()) {
            throw new AuthenticationFailureException();
        }
        String fingerprintSource = address + "|" + (userAgent == null ? "" : userAgent);
        LoginResponse response = refreshTokenService.refresh(new RefreshTokenService.RefreshRequest(
                refreshToken, idempotencyKey, TokenDigest.sha256("refresh-client|" + fingerprintSource)));
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        var cookie = RefreshCookieSupport.from(response);
        if (cookie != null) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return responseBuilder.body(response);
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

    /**
     * Optional JSON refresh payload. A blank value permits the secure refresh cookie fallback.
     *
     * @param refreshToken raw refresh credential
     */
    public record RefreshRequestBody(String refreshToken) {
    }
}
