package com.lifeos.identity.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the passkey REST boundary keeps protocol failures generic and returns the shared login
 * response on success.
 */
@ExtendWith(MockitoExtension.class)
class PasskeyControllerTest {

    @Mock
    private PasskeyAuthenticationService authenticationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PasskeyController(authenticationService)).build();
    }

    @Test
    void optionsReturnsOpaqueChallengeAndPublicKeyOptions() throws Exception {
        when(authenticationService.begin(any())).thenReturn(new PasskeyAuthenticationOptions(
                "c".repeat(43), objectMapper.readTree("{\"challenge\":\"challenge\"}")));

        mockMvc.perform(post("/api/v1/auth/passkey/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeId").value("c".repeat(43)))
                .andExpect(jsonPath("$.publicKey.challenge").value("challenge"));
    }

    @Test
    void successfulAssertionReturnsSharedSessionResponse() throws Exception {
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(authenticationService.complete(any(PasskeyAuthenticationRequest.class), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/passkey/assertion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","credential":{"id":"credential","response":{}}}
                                """.formatted("c".repeat(43))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(response.sessionId().toString()))
                .andExpect(jsonPath("$.accessToken").value("signed-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void invalidAssertionReturnsGenericUnauthorizedProblemWithoutEchoingCredential() throws Exception {
        when(authenticationService.complete(any(PasskeyAuthenticationRequest.class), any()))
                .thenThrow(new AuthenticationFailureException());

        mockMvc.perform(post("/api/v1/auth/passkey/assertion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","credential":{"id":"secret-credential","response":{}}}
                                """.formatted("c".repeat(43))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("passkey authentication request")))
                .andExpect(content().string(not(containsString("secret-credential"))));
    }

    @Test
    void rateLimitedAssertionReturnsRetryAfterWithoutEchoingRequest() throws Exception {
        when(authenticationService.complete(any(PasskeyAuthenticationRequest.class), any()))
                .thenThrow(new LoginRateLimitExceededException(60));

        mockMvc.perform(post("/api/v1/auth/passkey/assertion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"challengeId":"%s","credential":{"id":"secret-credential","response":{}}}
                                """.formatted("c".repeat(43))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(content().string(not(containsString("secret-credential"))));
    }

    @Test
    void malformedAssertionReturnsGenericBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/passkey/assertion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"short\","))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("passkey authentication request")));
    }
}
