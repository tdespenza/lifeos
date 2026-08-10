package com.lifeos.identity.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Browser-safe authorization start request carrying the client-generated PKCE pair.
 *
 * <p>The verifier is accepted only in the request body and is retained in the short-lived,
 * single-use callback state. It is never placed in the provider redirect URI.
 *
 * @param codeChallenge RFC 7636 S256 challenge
 * @param codeChallengeMethod supported PKCE method
 * @param codeVerifier RFC 7636 verifier paired with the challenge
 */
public record OidcAuthorizationStartRequest(
        @JsonProperty("code_challenge")
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._~-]{43,128}")
        String codeChallenge,
        @JsonProperty("code_challenge_method")
        @NotBlank
        @Pattern(regexp = "S256")
        String codeChallengeMethod,
        @JsonProperty("code_verifier")
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._~-]{43,128}")
        String codeVerifier) {

    /**
     * Converts the request to the provider-facing challenge contract.
     *
     * @return challenge-only authorization request
     */
    public OidcAuthorizationRequest challengeRequest() {
        return new OidcAuthorizationRequest(codeChallenge, codeChallengeMethod);
    }
}
