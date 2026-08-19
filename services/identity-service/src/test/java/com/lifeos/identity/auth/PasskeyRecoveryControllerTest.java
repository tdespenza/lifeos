package com.lifeos.identity.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PasskeyRecoveryControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private PasskeyRecoveryService recoveryService;

    @Mock
    private JwtValidationService validationService;

    @Mock
    private ClientAddressResolver clientAddressResolver;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new PasskeyRecoveryController(recoveryService, validationService, clientAddressResolver))
                .build();
        when(clientAddressResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    @Test
    void generatesCodesOnlyForAnAuthenticatedBearer() throws Exception {
        when(validationService.validate("access-token"))
                .thenReturn(new AuthenticatedSubject(ACCOUNT_ID, SESSION_ID, "PASSWORD", "proof"));
        when(recoveryService.generate(any(), any()))
                .thenReturn(new PasskeyRecoveryResult(
                        List.of("ABCD-EFGH-JKLM"), Instant.parse("2026-01-01T00:15:00Z")));

        mvc.perform(post("/api/v1/auth/passkey/recovery-codes")
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codes[0]").value("ABCD-EFGH-JKLM"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getHeader("Cache-Control"))
                        .isEqualTo("no-store"));

        verify(recoveryService).generate(any(AuthenticatedSubject.class), org.mockito.ArgumentMatchers.eq("127.0.0.1"));
    }

    @Test
    void recoversWithTheValidatedCodeEnvelopeAndReturnsTokens() throws Exception {
        LoginResponse response = new LoginResponse(UUID.randomUUID(), "access", "Bearer", 300);
        when(recoveryService.recover(any(), any(), any())).thenReturn(response);

        mvc.perform(post("/api/v1/auth/passkey/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"code\":\"ABCD-EFGH-JKLM\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"accessToken\":\"access\",\"tokenType\":\"Bearer\"}"));

        verify(recoveryService).recover(
                any(PasskeyRecoveryRequest.class),
                org.mockito.ArgumentMatchers.eq("127.0.0.1"),
                any(DeviceMetadata.class));
    }

    @Test
    void exposesSessionCapacityAsAConflict() throws Exception {
        when(recoveryService.recover(any(), any(), any())).thenThrow(new SessionCapacityExceededException());

        mvc.perform(post("/api/v1/auth/passkey/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"code\":\"ABCD-EFGH-JKLM\"}"))
                .andExpect(status().isConflict());
    }
}
