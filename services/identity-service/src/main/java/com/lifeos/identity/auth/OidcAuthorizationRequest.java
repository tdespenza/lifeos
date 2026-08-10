package com.lifeos.identity.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Client-supplied PKCE challenge for an OIDC authorization request.
 *
 * @param codeChallenge RFC 7636 S256 challenge
 * @param codeChallengeMethod supported PKCE method
 */
public record OidcAuthorizationRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._~-]{43,128}")
        String codeChallenge,
        @NotBlank
        @Pattern(regexp = "S256")
        String codeChallengeMethod) {
}
