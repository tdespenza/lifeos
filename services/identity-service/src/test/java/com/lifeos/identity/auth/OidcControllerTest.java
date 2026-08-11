package com.lifeos.identity.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the browser-safe authorization start accepts the PKCE pair without exposing the
 * verifier in the provider redirect contract.
 */
@ExtendWith(MockitoExtension.class)
class OidcControllerTest {

    private static final String PROVIDER_NAME = "example";
    private static final String VERIFIER = "a-verifier-with-43-characters-012345678901234";

    @Mock
    private OidcAuthenticationService authenticationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OidcController(authenticationService)).build();
    }

    @Test
    void formAuthorizationStartStoresVerifierThroughServiceAndRedirects() throws Exception {
        URI providerRedirect = URI.create("https://issuer.example/authorize?state=state");
        when(authenticationService.begin(
                eq(PROVIDER_NAME),
                eq(new OidcAuthorizationRequest(codeChallenge(VERIFIER), "S256")),
                eq(VERIFIER)))
                .thenReturn(providerRedirect);

        mockMvc.perform(post("/api/v1/auth/oidc/{provider}/authorize", PROVIDER_NAME)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code_challenge", codeChallenge(VERIFIER))
                        .param("code_challenge_method", "S256")
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", providerRedirect.toString()));

        verify(authenticationService).begin(
                eq(PROVIDER_NAME),
                eq(new OidcAuthorizationRequest(codeChallenge(VERIFIER), "S256")),
                eq(VERIFIER));
    }

    @Test
    void jsonAuthorizationStartAcceptsProtocolFieldNames() throws Exception {
        URI providerRedirect = URI.create("https://issuer.example/authorize?state=state");
        when(authenticationService.begin(
                eq(PROVIDER_NAME),
                eq(new OidcAuthorizationRequest(codeChallenge(VERIFIER), "S256")),
                eq(VERIFIER)))
                .thenReturn(providerRedirect);

        mockMvc.perform(post("/api/v1/auth/oidc/{provider}/authorize", PROVIDER_NAME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code_challenge":"%s","code_challenge_method":"S256","code_verifier":"%s"}
                                """.formatted(codeChallenge(VERIFIER), VERIFIER)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", providerRedirect.toString()));

        verify(authenticationService).begin(
                eq(PROVIDER_NAME),
                eq(new OidcAuthorizationRequest(codeChallenge(VERIFIER), "S256")),
                eq(VERIFIER));
    }

    @Test
    void callbackUsesHeaderVerifierAndIgnoresQueryVerifier() throws Exception {
        LoginResponse response = new LoginResponse(
                java.util.UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(authenticationService.callback(
                eq(PROVIDER_NAME), eq("code"), eq("state"), eq(VERIFIER), isNull(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/auth/oidc/{provider}/callback", PROVIDER_NAME)
                        .param("code", "code")
                        .param("state", "state")
                        .param("code_verifier", "query-verifier-that-must-not-be-read")
                        .header("X-PKCE-Code-Verifier", VERIFIER))
                .andExpect(status().isOk());

        verify(authenticationService).callback(
                eq(PROVIDER_NAME), eq("code"), eq("state"), eq(VERIFIER), isNull(), any());
    }

    @Test
    void callbackMapsOidcFailureToUnauthorized() throws Exception {
        when(authenticationService.callback(any(), any(), any(), any(), any(), any()))
                .thenThrow(new OidcAuthenticationFailureException());

        mockMvc.perform(callbackRequest())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callbackMapsDependencyFailureToServiceUnavailable() throws Exception {
        when(authenticationService.callback(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AuthenticationDependencyUnavailableException());

        mockMvc.perform(callbackRequest())
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void callbackMapsSessionCapacityToConflict() throws Exception {
        when(authenticationService.callback(any(), any(), any(), any(), any(), any()))
                .thenThrow(new SessionCapacityExceededException());

        mockMvc.perform(callbackRequest())
                .andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callbackRequest() {
        return get("/api/v1/auth/oidc/{provider}/callback", PROVIDER_NAME)
                .param("code", "code")
                .param("state", "state")
                .header("X-PKCE-Code-Verifier", VERIFIER);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
