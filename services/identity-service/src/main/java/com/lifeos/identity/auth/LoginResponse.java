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

    /**
     * Returns the durable session identifier.
     *
     * @return session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * JavaBean accessor for the durable session identifier.
     *
     * @return session identifier
     */
    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns the raw access JWT at the response boundary.
     *
     * @return access JWT
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * JavaBean accessor for the raw access JWT.
     *
     * @return access JWT
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Returns the OAuth token type.
     *
     * @return token type
     */
    public String tokenType() {
        return tokenType;
    }

    /**
     * JavaBean accessor for the OAuth token type.
     *
     * @return token type
     */
    public String getTokenType() {
        return tokenType;
    }

    /**
     * Returns the access-token lifetime in seconds.
     *
     * @return access-token lifetime
     */
    public long expiresIn() {
        return expiresIn;
    }

    /**
     * JavaBean accessor for the access-token lifetime.
     *
     * @return access-token lifetime
     */
    public long getExpiresIn() {
        return expiresIn;
    }

    /**
     * Returns the raw refresh credential at the response boundary.
     *
     * @return refresh credential, or {@code null} for compatibility responses
     */
    public String refreshToken() {
        return refreshToken;
    }

    /**
     * JavaBean accessor for the raw refresh credential.
     *
     * @return refresh credential, or {@code null} for compatibility responses
     */
    public String getRefreshToken() {
        return refreshToken;
    }

    /**
     * Returns the refresh-token lifetime in seconds.
     *
     * @return refresh-token lifetime
     */
    public long refreshExpiresIn() {
        return refreshExpiresIn;
    }

    /**
     * JavaBean accessor for the refresh-token lifetime.
     *
     * @return refresh-token lifetime
     */
    public long getRefreshExpiresIn() {
        return refreshExpiresIn;
    }

    /**
     * Compares all response fields, including refresh metadata.
     *
     * @param other object to compare
     * @return true when the response values match
     */
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

    /**
     * Returns a hash derived from all response fields.
     *
     * @return response hash
     */
    @Override
    public int hashCode() {
        return Objects.hash(sessionId, accessToken, tokenType, expiresIn, refreshToken, refreshExpiresIn);
    }

    /**
     * Returns a redacted diagnostic representation without token values.
     *
     * @return redacted response representation
     */
    @Override
    public String toString() {
        return "LoginResponse[sessionId=" + sessionId + ", accessToken=[redacted], tokenType="
                + tokenType + ", expiresIn=" + expiresIn + ", refreshToken=[redacted], refreshExpiresIn="
                + refreshExpiresIn + "]";
    }
}
