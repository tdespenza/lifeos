package com.lifeos.identity.auth;

import java.util.UUID;
import java.util.Objects;

/**
 * Versioned authentication result shared by password, OIDC, passkey, and refresh flows.
 *
 * <p>The raw access and refresh values exist only in this response boundary. Their digests are the
 * only durable representations.
 */
public final class LoginResponse {

    private final UUID sessionId;
    private final String accessToken;
    private final String tokenType;
    private final long expiresIn;
    private final String refreshToken;
    private final long refreshExpiresIn;

    /**
     * Compatibility constructor used by older test doubles and callers that do not issue refresh
     * credentials.
     */
    public LoginResponse(UUID sessionId, String accessToken, String tokenType, long expiresIn) {
        this(sessionId, accessToken, tokenType, expiresIn, null, 0);
    }

    /**
     * Creates a complete access/refresh response.
     */
    public LoginResponse(
            UUID sessionId,
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn) {
        this.sessionId = sessionId;
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
        this.refreshToken = refreshToken;
        this.refreshExpiresIn = refreshExpiresIn;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String accessToken() {
        return accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String tokenType() {
        return tokenType;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long expiresIn() {
        return expiresIn;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public long refreshExpiresIn() {
        return refreshExpiresIn;
    }

    public long getRefreshExpiresIn() {
        return refreshExpiresIn;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LoginResponse that)) {
            return false;
        }
        return expiresIn == that.expiresIn
                && refreshExpiresIn == that.refreshExpiresIn
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(accessToken, that.accessToken)
                && Objects.equals(tokenType, that.tokenType)
                && Objects.equals(refreshToken, that.refreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, accessToken, tokenType, expiresIn, refreshToken, refreshExpiresIn);
    }

    @Override
    public String toString() {
        return "LoginResponse[sessionId=" + sessionId + ", accessToken=[redacted], tokenType="
                + tokenType + ", expiresIn=" + expiresIn + ", refreshToken=[redacted], refreshExpiresIn="
                + refreshExpiresIn + "]";
    }
}
