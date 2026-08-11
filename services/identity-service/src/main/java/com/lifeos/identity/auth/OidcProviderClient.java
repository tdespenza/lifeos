package com.lifeos.identity.auth;

/**
 * Exchanges an authorization code and validates the provider ID token.
 */
public interface OidcProviderClient {

    /**
     * Exchanges the code and validates the ID token's issuer, audience, signature, time claims,
     * and nonce. Provider access tokens remain inside the provider client and are never returned.
     *
     * @param provider configured provider
     * @param code authorization code
     * @param codeVerifier PKCE verifier supplied by the client
     * @param nonce expected OIDC nonce
     * @return minimal verified identity
     */
    OidcIdentity exchangeAndValidate(
            IdentityAuthProperties.Provider provider, String code, String codeVerifier, String nonce);
}
