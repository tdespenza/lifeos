package com.lifeos.identity.auth;

/**
 * Single-use state retained between authorization redirect and provider callback.
 *
 * @param provider allow-listed provider name
 * @param redirectUri configured callback URI
 * @param codeChallenge client-generated PKCE S256 challenge
 * @param codeChallengeMethod PKCE method, currently S256 only
 * @param nonce OIDC replay-protection nonce
 * @param codeVerifier optional server-held PKCE verifier for browser callbacks; when non-null,
 *     client-held PKCE proof is not required at callback time
 * @param browserTransactionHash SHA-256 hash of the HttpOnly browser transaction cookie required
 *     to consume browser callback state
 */
public record OidcAuthorizationState(
        String provider,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String nonce,
        String codeVerifier,
        String browserTransactionHash) {

    /**
     * Redacts nonce, challenge, and verifier material from diagnostics and logs.
     *
     * @return diagnostic representation without protocol secrets
     */
    @Override
    public String toString() {
        return "OidcAuthorizationState[provider=" + provider
                + ", redirectUri=" + redirectUri
                + ", codeChallengeMethod=" + codeChallengeMethod
                + ", codeChallenge=<redacted>, nonce=<redacted>, codeVerifier=<redacted>"
                + ", browserTransactionHash=<redacted>]";
    }

    /**
     * Creates explicit browser state using the server-held verifier exception.
     *
     * @param provider allow-listed provider name
     * @param redirectUri configured callback URI
     * @param codeChallenge client-generated PKCE S256 challenge
     * @param codeChallengeMethod PKCE method
     * @param nonce OIDC nonce
     * @param codeVerifier client-generated verifier retained for the browser callback
     * @param browserTransactionHash cookie hash required to consume the callback state
     * @return browser callback state
     */
    public static OidcAuthorizationState forBrowserRedirect(
            String provider,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce,
            String codeVerifier,
            String browserTransactionHash) {
        return new OidcAuthorizationState(
                provider, redirectUri, codeChallenge, codeChallengeMethod, nonce, codeVerifier,
                browserTransactionHash);
    }

    /**
     * Creates callback state for clients that retain and forward the verifier themselves.
     *
     * @param provider allow-listed provider name
     * @param redirectUri configured callback URI
     * @param codeChallenge client-generated PKCE challenge
     * @param codeChallengeMethod PKCE method
     * @param nonce OIDC nonce
     */
    public OidcAuthorizationState(
            String provider,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce) {
        this(provider, redirectUri, codeChallenge, codeChallengeMethod, nonce, null, null);
    }
}
