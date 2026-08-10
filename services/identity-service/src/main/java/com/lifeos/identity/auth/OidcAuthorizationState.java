package com.lifeos.identity.auth;

/**
 * Single-use state retained between authorization redirect and provider callback.
 *
 * @param provider allow-listed provider name
 * @param redirectUri configured callback URI
 * @param codeChallenge client-generated PKCE S256 challenge
 * @param codeChallengeMethod PKCE method, currently S256 only
 * @param nonce OIDC replay-protection nonce
 * @param codeVerifier optional server-held PKCE verifier for browser callbacks
 */
public record OidcAuthorizationState(
        String provider,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String nonce,
        String codeVerifier) {

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
        this(provider, redirectUri, codeChallenge, codeChallengeMethod, nonce, null);
    }
}
