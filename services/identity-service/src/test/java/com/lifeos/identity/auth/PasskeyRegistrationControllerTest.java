package com.lifeos.identity.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PasskeyRegistrationControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);

    @Mock
    private PasskeyRegistrationService registrationService;

    @Mock
    private JwtValidationService validationService;

    @Mock
    private ClientAddressResolver clientAddressResolver;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new PasskeyRegistrationController(registrationService, validationService, clientAddressResolver))
                .build();
        org.mockito.Mockito.lenient().when(clientAddressResolver.resolve(any())).thenReturn("127.0.0.1");
        org.mockito.Mockito.lenient().when(validationService.validate("access-token"))
                .thenReturn(new AuthenticatedSubject(ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF));
    }

    @Test
    void requiresBearerAuthenticationBeforeStartingRegistration() throws Exception {
        mvc.perform(post("/api/v1/auth/passkey/registration/options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startsRegistrationWithTheValidatedSubject() throws Exception {
        when(registrationService.begin(any(), any()))
                .thenReturn(new PasskeyRegistrationOptions("c".repeat(43), JsonNodeFactory.instance.objectNode()));

        mvc.perform(post("/api/v1/auth/passkey/registration/options")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"challengeId\":\"" + "c".repeat(43) + "\",\"publicKey\":{}}"));

        verify(registrationService).begin(any(AuthenticatedSubject.class), org.mockito.ArgumentMatchers.eq("127.0.0.1"));
    }

    @Test
    void completesRegistrationWithTheValidatedSubjectAndOpaqueCredentialEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/passkey/registration")
                        .header("Authorization", "Bearer access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + "c".repeat(43)
                                + "\",\"credential\":{\"id\":\"credential\",\"response\":{}}}"))
                .andExpect(status().isNoContent());

        verify(registrationService).complete(
                any(AuthenticatedSubject.class), any(PasskeyRegistrationRequest.class),
                org.mockito.ArgumentMatchers.eq("127.0.0.1"));
    }

    @Test
    void listsOnlyNonSensitiveCredentialMetadata() throws Exception {
        UUID credentialId = UUID.randomUUID();
        when(registrationService.list(any()))
                .thenReturn(List.of(new PasskeyCredentialSummary(credentialId, Instant.parse("2026-01-01T00:00:00Z"), null)));

        mvc.perform(get("/api/v1/auth/passkey/credentials")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(credentialId.toString()))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].lastUsedAt").doesNotExist())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Cache-Control"))
                        .isEqualTo("no-store"));
    }

    @Test
    void revokesAnAuthenticatedCredential() throws Exception {
        UUID credentialId = UUID.randomUUID();

        mvc.perform(delete("/api/v1/auth/passkey/credentials/{id}", credentialId)
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isNoContent());

        verify(registrationService).revoke(any(AuthenticatedSubject.class), org.mockito.ArgumentMatchers.eq(credentialId),
                org.mockito.ArgumentMatchers.eq("127.0.0.1"));
    }
}
